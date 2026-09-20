package com.godviewer.app.target.control

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.backup.ThumbPayload
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.entry.EntryMode
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.edit.EditModeNotification
import com.godviewer.app.target.hook.hookers.ActivityLifecycleHooker
import com.godviewer.app.target.rule.ViewRuleManager
import com.godviewer.app.target.rule.ViewRuleThumbnails
import com.godviewer.app.target.ui.RuleManagerDialog
import com.google.gson.Gson

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
                    HostControlBridge.ACTION_IMPORT_RULES -> {
                        val json = intent.getStringExtra(HostControlBridge.EXTRA_RULES_JSON)
                        if (json.isNullOrBlank()) return
                        val incoming = runCatching {
                            Gson().fromJson(json, Array<ViewRule>::class.java)?.toList()
                        }.getOrNull()
                        if (incoming.isNullOrEmpty()) {
                            GvLog.w(TAG, "import rules: empty payload")
                            return
                        }
                        val batchId = intent.getStringExtra(HostControlBridge.EXTRA_BATCH_ID)
                        val (added, replaced) = ViewRuleManager.importRules(incoming, batchId)
                        GvLog.i(TAG, "import rules applied added=$added replaced=$replaced")
                        // 立刻回放当前页面，不用等下一个 Activity
                        Handler(Looper.getMainLooper()).post {
                            runCatching {
                                ActivityLifecycleHooker.replayCurrent(
                                    ActivityLifecycleHooker.resumedActivity(),
                                )
                            }.onFailure {
                                GvLog.e(TAG, "replay after import failed", it)
                            }
                        }
                    }
                    HostControlBridge.ACTION_IMPORT_THUMBS -> {
                        val json = intent.getStringExtra(HostControlBridge.EXTRA_THUMBS_JSON)
                        if (json.isNullOrBlank()) return
                        val raw = runCatching {
                            Gson().fromJson(json, Array<ThumbPayload>::class.java)
                        }.getOrNull()
                        if (raw.isNullOrEmpty()) {
                            GvLog.w(TAG, "import thumbs: empty payload")
                            return
                        }
                        val map = LinkedHashMap<String, ByteArray>()
                        for (item in raw) {
                            val key = item.key
                            val b64 = item.data
                            if (key.isNullOrBlank() || b64.isNullOrEmpty()) continue
                            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }
                                .getOrNull()
                            if (bytes != null && bytes.isNotEmpty()) map[key] = bytes
                        }
                        val written = ViewRuleThumbnails.importThumbs(map)
                        GvLog.i(TAG, "import thumbs written=$written of ${map.size}")
                    }
                    HostControlBridge.ACTION_UNDO_IMPORT -> {
                        val batchId = intent.getStringExtra(HostControlBridge.EXTRA_BATCH_ID)
                        if (batchId.isNullOrBlank()) return
                        val live = ActivityLifecycleHooker.liveActivities()
                        Handler(Looper.getMainLooper()).post {
                            runCatching {
                                val removed = ViewRuleManager.undoImport(batchId, live)
                                GvLog.i(TAG, "undo import batch=$batchId removed=$removed")
                                if (removed > 0) {
                                    ActivityLifecycleHooker.replayCurrent(
                                        ActivityLifecycleHooker.resumedActivity(),
                                    )
                                }
                            }.onFailure {
                                GvLog.e(TAG, "undo import failed", it)
                            }
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
            addAction(HostControlBridge.ACTION_IMPORT_RULES)
            addAction(HostControlBridge.ACTION_IMPORT_THUMBS)
            addAction(HostControlBridge.ACTION_UNDO_IMPORT)
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
