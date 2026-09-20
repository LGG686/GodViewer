package com.godviewer.app.target.control

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.entry.EntryMode
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.edit.EditModeNotification
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.ui.RuleManagerDialog

/**
 * Target-process receiver:
 * - host notification commands (enable / undo / manage rules)
 * - host entry-mode sync (cache mode + cancel/post target notification)
 */
object TargetControlReceiver {
    private const val TAG = "Control"
    private var registered = false
    private var appRef: Application? = null

    fun init(app: Application) {
        appRef = app
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    GvLog.w(TAG, "reject command: bad token")
                    return
                }
                when (intent.action) {
                    HostControlBridge.ACTION_ENABLE_EDIT -> {
                        GvLog.i(TAG, "enable edit received")
                        // setEnabled 内部会 refreshActivity / unwrap 或 wrap
                        EditMode.setEnabled(true)
                    }
                    HostControlBridge.ACTION_TOGGLE_EDIT -> {
                        // 点通知本体 = 开关：宿主不知道目标的真实状态时会用它
                        val next = !EditMode.isEnabled()
                        GvLog.i(TAG, "toggle edit received -> $next")
                        EditMode.setEnabled(next)
                    }
                    HostControlBridge.ACTION_DISABLE_EDIT -> {
                        GvLog.i(TAG, "disable edit received")
                        EditMode.setEnabled(false)
                    }
                    HostControlBridge.ACTION_UNDO -> {
                        val activity = ActivityLifecycleHooker.resumedActivity()
                        val undone = ViewRuleManager.undoLastOperation(activity)
                        GvLog.d(TAG, "undo received, undone=$undone")
                    }
                    HostControlBridge.ACTION_MANAGE_RULES -> {
                        val activity = ActivityLifecycleHooker.resumedActivity()
                        if (activity == null) {
                            GvLog.d(TAG, "manage rules: no resumed activity")
                            return
                        }
                        Handler(Looper.getMainLooper()).post {
                            runCatching { RuleManagerDialog(activity).show() }
                                .onFailure { GvLog.e(TAG, "show rule manager failed", it) }
                        }
                    }
                    EntryMode.ACTION_ENTRY_MODE_CHANGED -> {
                        val appCtx = appRef ?: context.applicationContext as? Application
                        if (appCtx == null) return
                        // 直接用 extras 写入本地缓存，不依赖目标再读宿主 prefs
                        val mode = intent.getStringExtra(EntryMode.EXTRA_MODE)
                        EntryMode.applyFromHost(appCtx, mode)
                        GvLog.d(TAG, "entry mode synced mode=$mode")
                        // 入口切到「不显示通知」：通知一撤就再没有退出入口了，
                        // 必须一并退出编辑模式，否则触摸拦截会一直吃掉目标 App 的点击。
                        // setEnabled(false) 内部会刷新通知，下面那次 refresh 只是兜底。
                        if (mode == EntryMode.NONE && EditMode.isEnabled()) {
                            GvLog.i(TAG, "entry hidden: exit edit mode")
                            Handler(Looper.getMainLooper()).post {
                                runCatching { EditMode.setEnabled(false) }
                            }
                        }
                        Handler(Looper.getMainLooper()).post {
                            runCatching { EditModeNotification.refresh(appCtx) }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(HostControlBridge.ACTION_ENABLE_EDIT)
            addAction(HostControlBridge.ACTION_TOGGLE_EDIT)
            addAction(HostControlBridge.ACTION_DISABLE_EDIT)
            addAction(HostControlBridge.ACTION_UNDO)
            addAction(HostControlBridge.ACTION_MANAGE_RULES)
            addAction(EntryMode.ACTION_ENTRY_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        GvLog.d(TAG, "target control receiver registered")
    }
}
