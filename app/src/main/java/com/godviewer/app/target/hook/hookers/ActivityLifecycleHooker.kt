package com.godviewer.app.target.hook.hookers

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import com.godviewer.app.shared.GvLog
import com.godviewer.app.target.dialog.ModuleDialogUi
import com.godviewer.app.target.edit.EditModeNotification
import com.godviewer.app.target.edit.SelectedViewHighlight
import com.godviewer.app.target.ui.RuleManagerDialog
import com.godviewer.app.target.hook.GvHook
import com.godviewer.app.target.hook.GvMethodHook
import com.godviewer.app.target.hook.IHooker
import com.godviewer.app.target.hook.MethodHookParam
import com.godviewer.app.target.mirror.ThumbnailSync
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.rule.findViewBestMatch
import java.util.Collections
import java.util.WeakHashMap

/**
 * 重放持久化规则（GodMode ActivityLifecycleHook 移植，只使用公共 API）：
 *
 * - hook Activity.onPostResume：Activity 恢复时应用该 Activity 的全部规则
 * - 每个 Activity 注册一次 onGlobalLayoutListener：布局变化（列表刷新、
 *   数据加载完成等）后重新应用规则
 * - onDestroy 时移除监听，WeakHashMap 防泄漏
 */
class ActivityLifecycleHooker : IHooker {

    private val layoutListeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    companion object {
        private const val TAG = "Lifecycle"

        /** 最近一次 onPostResume 的 Activity（撤销时用于回放当前界面的视图） */
        @Volatile
        private var resumedActivity: Activity? = null

        /** 存活（已 onPostResume 且未 onDestroy）的 Activity，删除规则时用于还原各 Activity 中的视图 */
        private val liveActivities =
            Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

        /** 待处理的「打开规则管理」请求时间戳（0 = 无）。 */
        @Volatile
        private var manageRulesRequestedAt = 0L

        /** 弹窗请求的有效期：太久之前的点击不该在无关界面上突然弹出来。 */
        private const val MANAGE_RULES_REQUEST_TTL_MS = 60_000L

        fun resumedActivity(): Activity? = resumedActivity

        fun liveActivities(): Set<Activity> = liveActivities

        /**
         * 请求在下一个前台 Activity 上打开规则管理弹窗。
         *
         * 通知栏点「规则」时目标常常还没回到前台（或在厂商的后台弹窗限制下拿不到可用的
         * 窗口），旧实现直接把这次点击丢掉，用户看到的就是「点了没反应」。改成记一笔请求，
         * 下一个 Activity resume 时补弹 —— 代价只是弹窗晚几百毫秒，比丢掉强得多。
         */
        fun requestManageRulesDialog() {
            manageRulesRequestedAt = System.currentTimeMillis()
        }

        /** 取出待处理的弹窗请求（超过 [MANAGE_RULES_REQUEST_TTL_MS] 视为过期）。 */
        private fun consumeManageRulesRequest(): Boolean {
            val requestedAt = manageRulesRequestedAt
            if (requestedAt == 0L) return false
            manageRulesRequestedAt = 0L
            return System.currentTimeMillis() - requestedAt <= MANAGE_RULES_REQUEST_TTL_MS
        }

        /**
         * 对指定 Activity 重放规则（导入规则后立刻生效用）。
         * 未 resumed 或该 Activity 无规则时安全返回。
         */
        fun replayCurrent(activity: Activity?) {
            if (activity == null) return
            runCatching { replay(activity, captureThumbs = true) }
                .onFailure { GvLog.e(TAG, "manual replay failed", it) }
        }

        private fun replay(activity: Activity, captureThumbs: Boolean = true) {
            val activityClass = runCatching { activity.componentName?.className }.getOrNull()
                ?: return
            val rules = runCatching { ViewRuleManager.rulesForActivity(activityClass) }
                .getOrDefault(emptyList())
            if (rules.isEmpty()) return
            for (rule in rules) {
                runCatching {
                    val view = findViewBestMatch(activity, rule) ?: return@runCatching
                    // 必须在应用规则**之前**抓图：隐藏类规则应用后视图变 GONE，
                    // 再抓就永远抓不到（ViewSnapshot 对 GONE 直接返回 null），
                    // 备份导入的规则本就没有任何存货，会永久显示占位图标
                    if (captureThumbs) {
                        // 仅 Activity 恢复时补抓一次缩略图（已有则跳过）；布局过程中跳过，
                        // 避免地图类等高频重布局场景每帧重抓导致掉帧。
                        ViewRuleManager.captureThumbnail(view, rule)
                    }
                    ViewRuleManager.applyRuleToView(view, rule)
                }.onFailure {
                    GvLog.e(TAG, "apply rule failed key=${rule.key()}", it)
                }
            }
            if (captureThumbs) {
                // 补抓到的新缩略图回推本体镜像（内部限频，best-effort）
                runCatching { ThumbnailSync.flushNewThumbnails(activity.application) }
                    .onFailure { GvLog.w(TAG, "flush new thumbnails failed", it) }
            }
        }
        /** 有补弹请求时延迟一小会儿弹出：等 Activity 布局稳定，避免 BadToken。 */
        private fun maybeShowRuleManager(activity: Activity) {
            if (!consumeManageRulesRequest()) return
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) {
                        return@postDelayed
                    }
                    RuleManagerDialog(activity).show()
                }.onFailure {
                    GvLog.e(TAG, "deferred rule manager dialog failed", it)
                }
            }, 200L)
        }
    }

    override fun onHook() {
        GvHook.findAndHookMethod(
            Activity::class.java,
            "onPostResume",
            object : GvMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 绝不能让规则重放 / window 访问异常拖垮目标 Activity
                    runCatching {
                        val activity = param.thisObject as? Activity ?: return
                        GvLog.d(TAG, "onPostResume ${activity.javaClass.name}")
                        resumedActivity = activity
                        liveActivities.add(activity)
                        // 弹窗 BadToken 回退：View.context 不是 Activity 时用
                        ModuleDialogUi.noteResumedActivity(activity)
                        replay(activity, captureThumbs = true)
                        registerLayoutListener(activity)
                        maybeShowRuleManager(activity)
                        // 回到前台时按宿主最新入口模式刷新通知：
                        // 宿主改设置后的广播可能漏投，这里兜底撤掉已隐藏的通知
                        EditModeNotification.refresh(activity.application)
                    }.onFailure {
                        GvLog.e(TAG, "onPostResume hook failed", it)
                    }
                }
            },
        )
        GvHook.findAndHookMethod(
            Activity::class.java,
            "onDestroy",
            object : GvMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val activity = param.thisObject as? Activity ?: return
                        if (resumedActivity === activity) {
                            resumedActivity = null
                            ModuleDialogUi.noteResumedActivity(null)
                        }
                        liveActivities.remove(activity)
                        unregisterLayoutListener(activity)
                        SelectedViewHighlight.clearIfActivity(activity)
                    }.onFailure {
                        GvLog.e(TAG, "onDestroy hook failed", it)
                    }
                }
            },
        )
    }

    private fun registerLayoutListener(activity: Activity) {
        if (layoutListeners.containsKey(activity)) {
            return
        }
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
        val vto = runCatching { decor.viewTreeObserver }.getOrNull() ?: return
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching { replay(activity, captureThumbs = false) }
                .onFailure { GvLog.e(TAG, "layout replay failed", it) }
        }
        runCatching { vto.addOnGlobalLayoutListener(listener) }
            .onSuccess { layoutListeners[activity] = listener }
            .onFailure { GvLog.e(TAG, "addOnGlobalLayoutListener failed", it) }
    }

    private fun unregisterLayoutListener(activity: Activity) {
        layoutListeners.remove(activity)?.let { listener ->
            runCatching {
                activity.window?.decorView?.viewTreeObserver
                    ?.removeOnGlobalLayoutListener(listener)
            }
        }
    }

}
