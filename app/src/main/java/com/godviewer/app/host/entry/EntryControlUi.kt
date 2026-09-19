package com.godviewer.app.host.entry

import android.content.Context
import com.godviewer.app.host.control.HiddenEntryNotifier
import com.godviewer.app.host.control.HostControlNotifier
import com.godviewer.app.shared.entry.EntryMode

/**
 * 宿主进程通知形态总控：
 * - 入口=本体通知：常驻 [HostControlNotifier]（打开应用 + 控制目标）
 * - 入口=目标通知：取消本体控制通知；若隐藏了桌面图标则只保留 [HiddenEntryNotifier]
 * - 入口=不显示通知：取消本体控制通知；仅当桌面图标已隐藏时保留 [HiddenEntryNotifier]，
 *   否则「隐藏通知 + 隐藏图标」会把用户彻底锁在应用外
 */
object EntryControlUi {
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
}
