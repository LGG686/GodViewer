package com.godviewer.app.host.entry

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.godviewer.app.host.control.HiddenEntryNotifier
import com.godviewer.app.host.control.HostControlNotifier
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.entry.EntryMode

/**
 * 宿主进程通知形态总控：
 * - 入口=本体通知：常驻 [HostControlNotifier]（打开应用 + 控制目标）
 * - 入口=目标通知：取消本体控制通知；若隐藏了桌面图标则只保留 [HiddenEntryNotifier]
 * - 入口=不显示通知：取消本体控制通知；仅当桌面图标已隐藏时保留 [HiddenEntryNotifier]，
 *   否则「隐藏通知 + 隐藏图标」会把用户彻底锁在应用外
 */
object EntryControlUi {
    /** 磁贴「开」的结果，调用方据此决定提示文案 */
    data class RestoreResult(
        /** 实际恢复到的入口模式 */
        val mode: String,
        /** 上次是本体入口但没有通知权限，已回落到目标应用入口 */
        val fellBackToTarget: Boolean,
        /** 「进入编辑模式」指令是否真的派发出去了（没有最近目标时为 false） */
        val editDispatched: Boolean,
        /** 开关关掉了：本次只恢复通知，不进编辑模式 */
        val enterEditDisabledByPrefs: Boolean,
    )

    fun refresh(context: Context) {
        val app = context.applicationContext
        when (EntryMode.current(app)) {
            EntryMode.HOST -> {
                // 本体控制通知已包含点开应用，不必再叠隐藏图标入口
                HiddenEntryNotifier.cancel(app)
                HostControlNotifier.refresh(app)
            }

            EntryMode.NONE -> {
                HostControlNotifier.cancel(app)
                HiddenEntryNotifier.refresh(app)
            }

            else -> {
                HostControlNotifier.cancel(app)
                HiddenEntryNotifier.refresh(app)
            }
        }
    }

    /** 改入口模式：持久化 + 推目标 + 刷新宿主通知形态。 */
    fun setEntryMode(context: Context, mode: String) {
        EntryMode.set(context, mode)
        refresh(context)
    }

    /**
     * 隐藏入口（磁贴「关」）：入口改为 [EntryMode.NONE]。
     *
     * 顺序不能反：先改模式，目标收到广播时读到的就是 NONE，
     * 于是「退出编辑模式 + 撤通知」一次完成；反过来会先把通知再贴一次。
     *
     * 之后补一条 [HostControlBridge.ACTION_DISABLE_EDIT] 给最近目标兜底：
     * 模式广播可能漏投（目标被冻结），这条显式指令是幂等的，重复执行只是再刷新一次通知。
     */
    fun hideEntry(context: Context) {
        val app = context.applicationContext
        EntryMode.set(app, EntryMode.NONE)
        HostControlBridge.dispatchToTarget(app, HostControlBridge.ACTION_DISABLE_EDIT)
        refresh(app)
    }

    /**
     * 恢复入口（磁贴「开」）：按 [EntryMode.lastVisible] 恢复；本体入口缺少通知权限时
     * 回落到目标应用入口（磁贴没有界面，弹不了授权框）。
     *
     * 先派发「进入编辑模式」再恢复入口：此时入口还是 NONE，目标不会先贴出一条
     * 「编辑模式已关闭」的通知再刷新，避免文案闪一下。
     */
    fun restoreEntry(context: Context): RestoreResult {
        val app = context.applicationContext
        var mode = EntryMode.lastVisible(app)
        var fellBack = false
        if (mode == EntryMode.HOST && !notificationsEnabled(app)) {
            mode = EntryMode.TARGET
            fellBack = true
        }
        val enterEdit = HostPrefs.isTileEnterEdit(app)
        // 没有记录到最近目标时派发会失败：目标进程不在，编辑模式开不了、通知也不会出现，
        // 调用方据此提示「下次打开目标应用时生效」
        val dispatched = if (enterEdit) {
            HostControlBridge.dispatchToTarget(app, HostControlBridge.ACTION_ENABLE_EDIT)
        } else {
            false
        }
        EntryMode.set(app, mode)
        refresh(app)
        return RestoreResult(
            mode = mode,
            fellBackToTarget = fellBack,
            editDispatched = dispatched,
            enterEditDisabledByPrefs = !enterEdit,
        )
    }

    private fun notificationsEnabled(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(true)
}
