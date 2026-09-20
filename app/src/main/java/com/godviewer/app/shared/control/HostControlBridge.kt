package com.godviewer.app.shared.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.godviewer.app.BuildConfig
import com.godviewer.app.shared.resolveAppLabel

/**
 * Cross-process control bridge between the host app notification and injected targets.
 *
 * Flow:
 * - Target onResume → report foreground package to host (explicit broadcast)
 * - Host notification actions → command the last reported target package
 * - Target keeps a dynamic receiver and executes enable / undo / manage-rules
 */
object HostControlBridge {
    private const val TAG = "GodViewer.Control"

    const val ACTION_TARGET_FOREGROUND =
        "${BuildConfig.PACKAGE_NAME}.ACTION_TARGET_FOREGROUND"
    const val ACTION_ENABLE_EDIT =
        "${BuildConfig.PACKAGE_NAME}.ACTION_ENABLE_EDIT"
    /** 宿主 → 目标：开关编辑模式（目标按自身状态取反） */
    const val ACTION_TOGGLE_EDIT =
        "${BuildConfig.PACKAGE_NAME}.ACTION_TOGGLE_EDIT"
    /** 宿主 → 目标：强制退出编辑模式（通知被隐藏时用） */
    const val ACTION_DISABLE_EDIT =
        "${BuildConfig.PACKAGE_NAME}.ACTION_DISABLE_EDIT"
    const val ACTION_UNDO =
        "${BuildConfig.PACKAGE_NAME}.ACTION_UNDO"
    const val ACTION_MANAGE_RULES =
        "${BuildConfig.PACKAGE_NAME}.ACTION_MANAGE_RULES"
    /** 目标 → 宿主：回写入口模式（目标通知「隐藏」按钮触发） */
    const val ACTION_SET_ENTRY_MODE =
        "${BuildConfig.PACKAGE_NAME}.ACTION_SET_ENTRY_MODE"
    /** 宿主 → 目标：导入备份规则（目标按键合并后写盘 + 立刻回放当前页面） */
    const val ACTION_IMPORT_RULES =
        "${BuildConfig.PACKAGE_NAME}.ACTION_IMPORT_RULES"
    /** 宿主 → 目标：下发导入的缩略图（分批：key → base64 图片） */
    const val ACTION_IMPORT_THUMBS =
        "${BuildConfig.PACKAGE_NAME}.ACTION_IMPORT_THUMBS"
    /** 宿主 → 目标：撤销一整批导入（按 batch id 整批移除） */
    const val ACTION_UNDO_IMPORT =
        "${BuildConfig.PACKAGE_NAME}.ACTION_UNDO_IMPORT"

    const val EXTRA_PACKAGE = "package_name"
    const val EXTRA_LABEL = "app_label"
    const val EXTRA_EDIT_ENABLED = "edit_enabled"
    const val EXTRA_ENTRY_MODE = "entry_mode"
    const val EXTRA_RULES_JSON = "rules_json"
    const val EXTRA_THUMBS_JSON = "thumbs_json"
    const val EXTRA_BATCH_ID = "batch_id"
    const val EXTRA_TOKEN = "token"
    const val CONTROL_TOKEN = "godviewer-host-control-v1"

    private const val HOST_RECEIVER = "com.godviewer.app.data.HostControlReceiver"
    private const val PREFS = "godviewer_host_control"
    private const val KEY_PACKAGE = "fg_package"
    private const val KEY_LABEL = "fg_label"
    private const val KEY_EDIT_ENABLED = "fg_edit_enabled"
    private const val KEY_UPDATED_AT = "fg_updated_at"

    data class TargetState(
        val packageName: String,
        val label: String,
        val editEnabled: Boolean,
        val updatedAt: Long,
    )

    // ---------- Target process ----------

    fun reportForeground(context: Context, packageName: String, editEnabled: Boolean) {
        runCatching {
            val app = context.applicationContext
            val label = resolveLabel(app, packageName)
            val intent = Intent(ACTION_TARGET_FOREGROUND).apply {
                component = ComponentName(BuildConfig.PACKAGE_NAME, HOST_RECEIVER)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_EDIT_ENABLED, editEnabled)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
            }
            app.sendBroadcast(intent)
            Log.d(TAG, "reportForeground pkg=$packageName edit=$editEnabled")
        }.onFailure {
            Log.w(TAG, "reportForeground failed", it)
        }
    }

    /**
     * 目标 → 宿主：回写入口模式。
     * best-effort：宿主未存活时目标靠自己的本地缓存保持「不显示通知」。
     */
    fun reportEntryMode(context: Context, mode: String) {
        runCatching {
            val app = context.applicationContext
            val intent = Intent(ACTION_SET_ENTRY_MODE).apply {
                component = ComponentName(BuildConfig.PACKAGE_NAME, HOST_RECEIVER)
                putExtra(EXTRA_ENTRY_MODE, mode)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
            }
            app.sendBroadcast(intent)
            Log.d(TAG, "reportEntryMode mode=$mode")
        }.onFailure {
            Log.w(TAG, "reportEntryMode failed", it)
        }
    }

    // ---------- Host process ----------

    fun saveTargetState(
        context: Context,
        packageName: String,
        label: String,
        editEnabled: Boolean,
    ) {
        val safePkg = packageName.trim()
        if (safePkg.isEmpty() || safePkg == BuildConfig.PACKAGE_NAME) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PACKAGE, safePkg)
            .putString(KEY_LABEL, label.ifBlank { safePkg })
            .putBoolean(KEY_EDIT_ENABLED, editEnabled)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun currentTarget(context: Context): TargetState? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_PACKAGE, null)?.trim().orEmpty()
        if (pkg.isEmpty()) return null
        return TargetState(
            packageName = pkg,
            label = prefs.getString(KEY_LABEL, pkg).orEmpty().ifBlank { pkg },
            editEnabled = prefs.getBoolean(KEY_EDIT_ENABLED, false),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    /** Clear remembered target (e.g. when host UI is opened again). */
    fun clearTargetState(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PACKAGE)
            .remove(KEY_LABEL)
            .remove(KEY_EDIT_ENABLED)
            .remove(KEY_UPDATED_AT)
            .apply()
        Log.d(TAG, "target state cleared")
    }

    fun dispatchToTarget(context: Context, action: String): Boolean {
        val target = currentTarget(context) ?: run {
            Log.d(TAG, "dispatch skip: no target for $action")
            return false
        }
        return runCatching {
            val intent = Intent(action).apply {
                setPackage(target.packageName)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                putExtra(EXTRA_PACKAGE, target.packageName)
            }
            context.applicationContext.sendBroadcast(intent)
            Log.d(TAG, "dispatch $action -> ${target.packageName}")
            true
        }.onFailure {
            Log.w(TAG, "dispatch failed action=$action", it)
        }.getOrDefault(false)
    }

    /**
     * 宿主 → 指定目标：下发导入的规则 JSON（按键合并，重复下发幂等）。
     *
     * 显式指定包名，不依赖「最近一个目标」的缓存记录——隐藏通知、
     * 从未进入编辑模式的应用也能收到。
     */
    fun dispatchImportRules(
        context: Context,
        packageName: String,
        rulesJson: String,
        batchId: String? = null,
    ): Boolean {
        val safePkg = packageName.trim()
        if (safePkg.isEmpty() || rulesJson.isBlank()) return false
        return runCatching {
            val intent = Intent(ACTION_IMPORT_RULES).apply {
                setPackage(safePkg)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                putExtra(EXTRA_PACKAGE, safePkg)
                putExtra(EXTRA_RULES_JSON, rulesJson)
                if (!batchId.isNullOrBlank()) putExtra(EXTRA_BATCH_ID, batchId)
            }
            context.applicationContext.sendBroadcast(intent)
            Log.d(TAG, "import rules dispatched -> $safePkg (${rulesJson.length} chars)")
            true
        }.onFailure {
            Log.w(TAG, "dispatch import rules failed", it)
        }.getOrDefault(false)
    }

    /**
     * 宿主 → 指定目标：下发导入的缩略图（key → base64），目标落盘后规则管理
     * 列表就能显示原设备上那个控件的样子。规则与缩略图分开发，各批都在 Binder 预算内。
     */
    fun dispatchImportThumbs(context: Context, packageName: String, thumbsJson: String): Boolean {
        val safePkg = packageName.trim()
        if (safePkg.isEmpty() || thumbsJson.isBlank()) return false
        return runCatching {
            val intent = Intent(ACTION_IMPORT_THUMBS).apply {
                setPackage(safePkg)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                putExtra(EXTRA_PACKAGE, safePkg)
                putExtra(EXTRA_THUMBS_JSON, thumbsJson)
            }
            context.applicationContext.sendBroadcast(intent)
            Log.d(TAG, "import thumbs dispatched -> $safePkg (${thumbsJson.length} chars)")
            true
        }.onFailure {
            Log.w(TAG, "dispatch import thumbs failed", it)
        }.getOrDefault(false)
    }

    /**
     * 宿主 → 指定目标：撤销一整批导入。
     *
     * 目标未运行时同样无法送达，这里只做 best-effort；宿主侧会自行重写镜像，
     * 目标下次启动时规则已不存在，不会误伤。
     */
    fun dispatchUndoImport(context: Context, packageName: String, batchId: String): Boolean {
        val safePkg = packageName.trim()
        if (safePkg.isEmpty() || batchId.isBlank()) return false
        return runCatching {
            val intent = Intent(ACTION_UNDO_IMPORT).apply {
                setPackage(safePkg)
                putExtra(EXTRA_TOKEN, CONTROL_TOKEN)
                putExtra(EXTRA_PACKAGE, safePkg)
                putExtra(EXTRA_BATCH_ID, batchId)
            }
            context.applicationContext.sendBroadcast(intent)
            Log.d(TAG, "undo import dispatched -> $safePkg batch=$batchId")
            true
        }.onFailure {
            Log.w(TAG, "dispatch undo import failed", it)
        }.getOrDefault(false)
    }

    private fun resolveLabel(context: Context, packageName: String): String {
        return resolveAppLabel(context, packageName)
    }
}
