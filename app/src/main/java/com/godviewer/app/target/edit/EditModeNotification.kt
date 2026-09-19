package com.godviewer.app.target.edit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.godviewer.app.R
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.entry.EntryMode
import com.godviewer.app.target.hook.ModuleRes
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.ui.RuleManagerDialog

/**
 * 目标应用进程内的编辑模式通知入口（默认入口）。
 *
 * 仅在入口=目标通知时发布；本体入口下必须 cancel。
 * 模式未确认前不 post，等宿主 push，避免本体入口下闪出目标通知。
 *
 * **moduleRes 未就绪时绝不 post**（部分进程/框架路径 zygote init 可能未跑到），
 * 否则 lateinit 会在 Application.onCreate 的延迟任务里直接闪退目标 App。
 */
object EditModeNotification {

    private const val TAG = "EditNotif"

    const val ACTION_TOGGLE = "com.godviewer.app.action.TOGGLE_EDIT_MODE"
    const val ACTION_UNDO = "com.godviewer.app.action.UNDO_LAST_OPERATION"
    const val ACTION_MANAGE_RULES = "com.godviewer.app.action.MANAGE_RULES"
    const val ACTION_HIDE = "com.godviewer.app.action.HIDE_ENTRY_NOTIFICATION"

    private const val CHANNEL_ID = "godviewer_edit_mode"
    private const val NOTIFICATION_ID = 0x4756 // "GV"
    private const val CONFIRM_WAIT_MS = 600L

    private var receiverRegistered = false
    private var appRef: Application? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingConfirm: Runnable? = null

    fun init(app: Application) {
        appRef = app
        runCatching { registerToggleReceiver(app) }
            .onFailure { GvLog.e(TAG, "register toggle receiver failed", it) }
        runCatching { refresh(app) }
            .onFailure { GvLog.e(TAG, "init refresh failed", it) }
    }

    fun refresh(app: Application? = appRef) {
        val application = app ?: appRef ?: return
        runCatching {
            // 先跟宿主对齐一次：宿主改了设置但广播漏投时，这里能补救
            EntryMode.syncFromHostBeforePost(application)
            // 已确认 host / none → 立刻撤掉
            if (!EntryMode.shouldShowTargetNotification(application)) {
                cancelPendingConfirm()
                cancel(application)
                GvLog.d(
                    TAG,
                    "entry=${EntryMode.currentFromModule(application)}: " +
                        "target notification cancelled",
                )
                return
            }
            // 已确认 target → 发通知
            if (EntryMode.hasConfirmedMode(application)) {
                cancelPendingConfirm()
                post(application)
                return
            }
            // 尚未确认：先 cancel 任何残留，等宿主 push；超时再按默认 target 发
            cancel(application)
            scheduleConfirmFallback(application)
            GvLog.d(TAG, "entry mode unconfirmed: wait host sync before post")
        }.onFailure {
            GvLog.e(TAG, "refresh failed", it)
        }
    }

    fun post(app: Application) {
        runCatching {
            if (!EntryMode.shouldShowTargetNotification(app)) {
                cancel(app)
                return
            }
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                GvLog.d(TAG, "notifications disabled, skip post")
                return
            }
            if (!ModuleRes.isModuleResReady()) {
                // 绝不能碰 lateinit moduleRes：会 UninitializedPropertyAccessException 闪退
                GvLog.w(
                    TAG,
                    "moduleRes not ready, skip target notification " +
                        "(zygote init missing or failed for this process)",
                )
                return
            }
            val res = ModuleRes.moduleRes
            val enabled = EditMode.isEnabled()
            val toggleIntent = PendingIntent.getBroadcast(
                app,
                0,
                Intent(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val undoIntent = PendingIntent.getBroadcast(
                app,
                1,
                Intent(ACTION_UNDO),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val manageRulesIntent = PendingIntent.getBroadcast(
                app,
                2,
                Intent(ACTION_MANAGE_RULES),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val hideIntent = PendingIntent.getBroadcast(
                app,
                3,
                Intent(ACTION_HIDE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(res.getString(R.string.edit_mode))
                // 正文实时反映当前模式，并说明点击后会做什么：
                // 点通知本体 = 开关编辑模式（开↔关），不必再去弹窗里点「退出编辑模式」
                .setContentText(
                    res.getString(
                        if (enabled) R.string.edit_mode_tap_to_disable
                        else R.string.edit_mode_tap_to_enable,
                    ),
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(toggleIntent)
                // 「隐藏」放在第一个 action 位：这个位置此前渲染的是「开启」，
                // 用户设备上已验证可见，所以关键操作放这里最稳
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    res.getString(R.string.edit_mode_hide),
                    hideIntent,
                )
                .addAction(
                    android.R.drawable.ic_menu_revert,
                    res.getString(R.string.undo),
                    undoIntent,
                )
                .addAction(
                    android.R.drawable.ic_menu_manage,
                    res.getString(R.string.manage_rules),
                    manageRulesIntent,
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        res.getString(R.string.edit_mode),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
            GvLog.d(TAG, "notification posted, enabled=$enabled")
        }.onFailure {
            GvLog.e(TAG, "post notification failed", it)
        }
    }

    fun cancel(app: Application) {
        runCatching {
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
        }
    }

    /**
     * 隐藏入口通知（唯一真入口：规则弹窗底部按钮 + 通知 action）。
     *
     * 不放在通知 action 上作为唯一入口是有原因的：SystemUI 对通知 action 的
     * 渲染数量各家 ROM 不一致（有的只渲染 2 个，第 3 个被静默丢弃），
     * 「隐藏」这种关键操作不能赌它显示得出来。规则弹窗是我们自己的
     * View，一定可见，所以把真正的入口放在那里。
     *
     * 顺序很关键：先回写宿主偏好，再动本地状态。退出编辑模式会向宿主上报
     * 前台状态，宿主随后把自己的偏好推回目标；若此时宿主仍是 target，
     * 通知会被重新推出来。
     */
    fun hideEntryNotification(context: Context?) {
        val ctx = context ?: appRef ?: return
        runCatching {
            GvLog.i(TAG, "hide entry notification")
            HostControlBridge.reportEntryMode(ctx, EntryMode.NONE)
            EntryMode.applyFromHost(ctx, EntryMode.NONE)
            // 通知消失后就没有退出入口了，必须一并退出编辑模式，
            // 否则触摸拦截会一直吃掉目标 App 的点击
            if (EditMode.isEnabled()) {
                EditMode.setEnabled(false)
            }
            appRef?.let { cancel(it) }
            runCatching {
                NotificationManagerCompat.from(ctx.applicationContext).cancel(NOTIFICATION_ID)
            }
            showHiddenToast(ctx)
        }.onFailure {
            GvLog.e(TAG, "hide entry notification failed", it)
        }
    }

    private fun scheduleConfirmFallback(app: Application) {
        cancelPendingConfirm()
        val task = Runnable {
            pendingConfirm = null
            runCatching {
                // 超时仍无宿主确认：按默认目标入口发（兼容宿主未存活）
                // none 不得走这条兜底，否则被隐藏的通知会「诈尸」
                if (EntryMode.shouldShowTargetNotification(app)) {
                    GvLog.d(TAG, "confirm timeout: post target notification by default")
                    post(app)
                } else {
                    cancel(app)
                }
            }.onFailure {
                GvLog.e(TAG, "confirm fallback failed", it)
            }
        }
        pendingConfirm = task
        mainHandler.postDelayed(task, CONFIRM_WAIT_MS)
    }

    private fun cancelPendingConfirm() {
        pendingConfirm?.let { mainHandler.removeCallbacks(it) }
        pendingConfirm = null
    }

    private fun registerToggleReceiver(app: Application) {
        if (receiverRegistered) {
            return
        }
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null) {
                    return
                }
                runCatching {
                    when (intent?.action) {
                        ACTION_UNDO -> {
                            val activity = ActivityLifecycleHooker.resumedActivity()
                            val undone = ViewRuleManager.undoLastOperation(activity)
                            GvLog.d(TAG, "undo received, undone=$undone")
                        }
                        ACTION_MANAGE_RULES -> {
                            val activity = ActivityLifecycleHooker.resumedActivity()
                            if (activity != null) {
                                runCatching { RuleManagerDialog(activity).show() }
                                    .onFailure { GvLog.e(TAG, "show rule manager failed", it) }
                            }
                        }
                        ACTION_HIDE -> hideEntryNotification(context)

                        ACTION_TOGGLE -> {
                            // 点通知本体 = 开关切换。之前只做「开启」，关闭编辑模式
                            // 只能靠点控件弹窗里的「退出编辑模式」，太绕
                            val next = !EditMode.isEnabled()
                            GvLog.i(TAG, "toggle received -> edit mode = $next")
                            EditMode.setEnabled(next)
                        }

                        else -> Unit
                    }
                }.onFailure {
                    GvLog.e(TAG, "notification action failed action=${intent?.action}", it)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE)
            addAction(ACTION_UNDO)
            addAction(ACTION_MANAGE_RULES)
            addAction(ACTION_HIDE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        GvLog.d(TAG, "notification receivers registered")
    }

    /**
     * 隐藏通知后唯一的反馈：告诉用户去哪儿恢复。
     * 目标进程内必须用 moduleRes 取字符串，不能碰宿主 R。
     */
    private fun showHiddenToast(context: Context) {
        runCatching {
            if (!ModuleRes.isModuleResReady()) return
            Toast.makeText(
                context.applicationContext,
                ModuleRes.moduleRes.getString(R.string.edit_mode_hidden_toast),
                Toast.LENGTH_LONG,
            ).show()
        }.onFailure {
            GvLog.e(TAG, "hide toast failed", it)
        }
    }
}
