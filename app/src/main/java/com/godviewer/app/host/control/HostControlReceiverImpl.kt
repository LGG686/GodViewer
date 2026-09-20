package com.godviewer.app.host.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.godviewer.app.R
import com.godviewer.app.host.control.HostControlNotifier
import com.godviewer.app.host.diag.HostCrashStore
import com.godviewer.app.host.diag.HostDiagStore
import com.godviewer.app.host.entry.EntryControlUi
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.diag.CrashReportProtocol
import com.godviewer.app.shared.diag.DiagReportProtocol
import com.godviewer.app.shared.entry.EntryMode

/**
 * Host-side control receiver:
 * - target foreground / edit-state reports
 * - notification body / actions（开启仅在未开时下发；已开只刷新通知）
 * - target **full debug ring** + crash dump（best-effort，供设置页复制）
 */
open class HostControlReceiverImpl : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val app = context.applicationContext
        val action = intent.action ?: return

        when (action) {
            DiagReportProtocol.ACTION_TARGET_DIAG -> {
                val token = intent.getStringExtra(DiagReportProtocol.EXTRA_TOKEN)
                if (token != DiagReportProtocol.DIAG_TOKEN) {
                    GvLog.w(TAG, "reject target diag: bad token")
                    return
                }
                val pkg = intent.getStringExtra(DiagReportProtocol.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                val body = intent.getStringExtra(DiagReportProtocol.EXTRA_BODY).orEmpty()
                if (body.isBlank()) return
                val meta = HostDiagStore.DiagMeta(
                    packageName = pkg,
                    processName = intent.getStringExtra(DiagReportProtocol.EXTRA_PROCESS)
                        .orEmpty().ifBlank { pkg },
                    stage = intent.getStringExtra(DiagReportProtocol.EXTRA_STAGE)
                        .orEmpty().ifBlank { "diag" },
                    timeMs = intent.getLongExtra(
                        DiagReportProtocol.EXTRA_TIME,
                        System.currentTimeMillis(),
                    ),
                    moduleVc = intent.getIntExtra(DiagReportProtocol.EXTRA_MODULE_VC, 0),
                    moduleVn = intent.getStringExtra(DiagReportProtocol.EXTRA_MODULE_VN).orEmpty(),
                    lineCount = intent.getIntExtra(DiagReportProtocol.EXTRA_LINE_COUNT, 0),
                    body = body,
                )
                val ok = HostDiagStore.save(app, meta)
                GvLog.d(
                    TAG,
                    "target diag received pkg=$pkg ok=$ok stage=${meta.stage} lines=${meta.lineCount}",
                )
            }

            CrashReportProtocol.ACTION_TARGET_CRASH -> {
                val token = intent.getStringExtra(CrashReportProtocol.EXTRA_TOKEN)
                if (token != CrashReportProtocol.CRASH_TOKEN) {
                    GvLog.w(TAG, "reject crash report: bad token")
                    return
                }
                val pkg = intent.getStringExtra(CrashReportProtocol.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                val meta = HostCrashStore.CrashMeta(
                    packageName = pkg,
                    processName = intent.getStringExtra(CrashReportProtocol.EXTRA_PROCESS)
                        .orEmpty().ifBlank { pkg },
                    threadName = intent.getStringExtra(CrashReportProtocol.EXTRA_THREAD)
                        .orEmpty().ifBlank { "?" },
                    summary = intent.getStringExtra(CrashReportProtocol.EXTRA_SUMMARY).orEmpty(),
                    timeMs = intent.getLongExtra(
                        CrashReportProtocol.EXTRA_TIME,
                        System.currentTimeMillis(),
                    ),
                    moduleVc = intent.getIntExtra(CrashReportProtocol.EXTRA_MODULE_VC, 0),
                    moduleVn = intent.getStringExtra(CrashReportProtocol.EXTRA_MODULE_VN).orEmpty(),
                    stack = intent.getStringExtra(CrashReportProtocol.EXTRA_STACK).orEmpty(),
                    ring = intent.getStringExtra(CrashReportProtocol.EXTRA_RING).orEmpty(),
                )
                val ok = HostCrashStore.save(app, meta)
                GvLog.i(
                    TAG,
                    "target crash received pkg=$pkg ok=$ok summary=${meta.summary.take(160)}",
                )
            }

            HostControlBridge.ACTION_TARGET_FOREGROUND -> {
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    GvLog.w(TAG, "reject foreground: bad token")
                    return
                }
                val pkg = intent.getStringExtra(HostControlBridge.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return
                val label = intent.getStringExtra(HostControlBridge.EXTRA_LABEL).orEmpty()
                val editEnabled = intent.getBooleanExtra(HostControlBridge.EXTRA_EDIT_ENABLED, false)
                HostControlBridge.saveTargetState(app, pkg, label, editEnabled)
                // 把当前入口模式推回目标，避免目标进程读不到 prefs 仍发自己的通知
                EntryMode.pushToTarget(app, pkg)
                // 仅本体入口展示控制通知；目标入口下 refresh 内部会 cancel
                HostControlNotifier.refresh(app)
                GvLog.d(TAG, "foreground pkg=$pkg edit=$editEnabled")
            }

            HostControlBridge.ACTION_SET_ENTRY_MODE -> {
                val token = intent.getStringExtra(HostControlBridge.EXTRA_TOKEN)
                if (token != HostControlBridge.CONTROL_TOKEN) {
                    GvLog.w(TAG, "reject set entry mode: bad token")
                    return
                }
                val mode = intent.getStringExtra(HostControlBridge.EXTRA_ENTRY_MODE).orEmpty()
                if (mode.isBlank()) return
                // 用户在目标通知上点了「隐藏」：宿主偏好同步为 none，并刷新本体通知形态
                EntryControlUi.setEntryMode(app, mode)
                GvLog.i(TAG, "entry mode set from target mode=$mode")
            }

            HostControlNotifier.ACTION_TOGGLE -> {
                // 与目标通知点击一致：点一下开，再点一下关
                val target = HostControlBridge.currentTarget(app)
                if (target == null) {
                    Toast.makeText(app, R.string.host_control_no_target, Toast.LENGTH_SHORT).show()
                    HostControlNotifier.refresh(app)
                    return
                }
                val ok = HostControlBridge.dispatchToTarget(
                    app,
                    HostControlBridge.ACTION_TOGGLE_EDIT,
                )
                GvLog.i(TAG, "toggle click: dispatch pkg=${target.packageName} ok=$ok")
                if (!ok) {
                    Toast.makeText(app, R.string.host_control_no_target, Toast.LENGTH_SHORT).show()
                }
                HostControlNotifier.refresh(app)
                // 目标上报是异步的，稍后再刷一次，拿到真实开关状态
                refreshLater(app)
            }

            HostControlNotifier.ACTION_HIDE -> {
                // 与目标通知的「隐藏」一致：入口改为不显示通知，并退出目标编辑模式
                val target = HostControlBridge.currentTarget(app)
                GvLog.i(TAG, "hide click pkg=${target?.packageName}")
                if (target != null && target.editEnabled) {
                    val ok = HostControlBridge.dispatchToTarget(
                        app,
                        HostControlBridge.ACTION_DISABLE_EDIT,
                    )
                    GvLog.i(TAG, "hide click: disable edit dispatch ok=$ok")
                }
                EntryControlUi.setEntryMode(app, EntryMode.NONE)
                HostControlNotifier.cancel(app)
                Toast.makeText(app, R.string.edit_mode_hidden_toast, Toast.LENGTH_LONG).show()
            }

            HostControlNotifier.ACTION_UNDO,
            HostControlNotifier.ACTION_MANAGE_RULES,
            -> {
                val targetAction = when (action) {
                    HostControlNotifier.ACTION_UNDO -> HostControlBridge.ACTION_UNDO
                    else -> HostControlBridge.ACTION_MANAGE_RULES
                }
                val ok = HostControlBridge.dispatchToTarget(app, targetAction)
                if (!ok) {
                    Toast.makeText(app, R.string.host_control_no_target, Toast.LENGTH_SHORT).show()
                }
            }

            else -> Unit
        }
    }

    /** 目标上报是异步广播，延迟再刷一次通知，让正文反映真实状态。 */
    private fun refreshLater(context: Context) {
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                HostControlNotifier.refresh(context.applicationContext)
            }, REFRESH_DELAY_MS)
        }.onFailure {
            GvLog.w(TAG, "schedule refresh failed", it)
        }
    }

    companion object {
        private const val TAG = "Control"
        private const val REFRESH_DELAY_MS = 600L
    }
}
