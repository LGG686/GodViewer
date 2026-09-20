package com.godviewer.app.host.backup

import android.content.Context
import android.util.Base64
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.backup.ThumbPayload
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import com.godviewer.app.shared.model.ViewRule
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream

/**
 * 宿主进程：把导入的规则（含缩略图）送进目标进程。
 *
 * 目标进程的 receiver 是动态注册的，进程不在时广播会被丢弃且无从得知，
 * 所以内容先落一份 pending 文件，等目标上报前台（[flush]）时再补发一次：
 *
 * - 目标正在运行：[deliver] 立即下发，页面立刻回放生效
 * - 目标未运行（换机 / 重装）：pending 留着，目标下次启动上报前台时补发
 *
 * 规则与缩略图分开发、各自分批，单批都控制在 Binder 事务预算内。
 * 重复导入是幂等的（目标侧按键合并），重复下发无害。
 */
internal object RuleBackupDelivery {
    private const val TAG = "Backup"
    /** 单批广播上限（与镜像缩略图批次一致，留足 Binder 余量）。 */
    private const val MAX_BATCH_BYTES = 300 * 1024
    private val gson = Gson()

    /** pending 文件内容：规则 + 缩略图（base64）+ 批次 id。字段可空兜住坏文件。 */
    private data class PendingImport(
        val rules: List<ViewRule>? = null,
        val thumbnails: List<ThumbPayload>? = null,
        val batchId: String? = null,
    )

    private fun pendingDir(context: Context): File =
        File(File(context.applicationContext.filesDir, "godviewer"), "backup_pending")

    fun deliver(
        context: Context,
        packageName: String,
        rules: List<ViewRule>,
        thumbnails: Map<String, ByteArray>,
        batchId: String?,
    ) {
        runCatching {
            val payloads = encodeThumbs(thumbnails)
            markPending(context, packageName, PendingImport(rules, payloads, batchId))
            sendBatches(context, packageName, rules, batchId)
            sendThumbBatches(context, packageName, payloads)
        }.onFailure {
            GvLog.w(TAG, "deliver failed", it)
        }
    }

    /** 目标已上报前台：补发 pending 内容并清除。 */
    fun flush(context: Context, packageName: String): Boolean {
        return runCatching {
            val pending = readPending(context, packageName) ?: return@runCatching false
            val sent = sendBatches(
                context,
                packageName,
                pending.rules.orEmpty(),
                pending.batchId,
            )
            sendThumbBatches(context, packageName, pending.thumbnails.orEmpty())
            if (sent) {
                clearPending(context, packageName)
            }
            sent
        }.getOrDefault(false)
    }

    /**
     * 撤销导入后清掉该包的 pending：否则目标下次上报前台时会把撤销掉的规则又补发回来。
     * 只清属于这一批的（batchId 对得上），别的批次留着。
     */
    fun clearPendingFor(context: Context, packageName: String, batchId: String?) {
        val pending = readPending(context, packageName)
        if (pending == null || batchId.isNullOrBlank() || pending.batchId == batchId) {
            clearPending(context, packageName)
        }
    }

    private fun encodeThumbs(thumbnails: Map<String, ByteArray>): List<ThumbPayload> {
        return thumbnails.mapNotNull { (key, raw) ->
            if (key.isBlank() || raw.isEmpty()) return@mapNotNull null
            ThumbPayload(key, Base64.encodeToString(raw, Base64.NO_WRAP))
        }
    }

    private fun markPending(context: Context, packageName: String, pending: PendingImport) {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return
        val dir = pendingDir(context)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$safePkg.json")
        val tmp = File(dir, "$safePkg.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(gson.toJson(pending).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        GvLog.d(TAG, "pending saved: $safePkg rules=${pending.rules.orEmpty().size}")
    }

    private fun readPending(context: Context, packageName: String): PendingImport? {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return null
        val file = File(pendingDir(context), "$safePkg.json")
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson(file.readText(), PendingImport::class.java)
        }.getOrNull()
    }

    private fun clearPending(context: Context, packageName: String) {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return
        File(pendingDir(context), "$safePkg.json").delete()
        GvLog.d(TAG, "pending cleared: $safePkg")
    }

    /** 按批下发规则：单批超过 Binder 预算就拆开，目标侧每批合并一次。 */
    private fun sendBatches(
        context: Context,
        packageName: String,
        rules: List<ViewRule>,
        batchId: String?,
    ): Boolean {
        if (rules.isEmpty()) return false
        var sent = false
        var batch = ArrayList<ViewRule>()
        var bytes = 0

        fun flushBatch() {
            if (batch.isEmpty()) return
            val json = gson.toJson(batch)
            if (HostControlBridge.dispatchImportRules(
                    context.applicationContext,
                    packageName,
                    json,
                    batchId,
                )
            ) {
                sent = true
            }
            batch = ArrayList()
            bytes = 0
        }

        for (rule in rules) {
            val size = runCatching { gson.toJson(rule).toByteArray(Charsets.UTF_8).size }
                .getOrDefault(0)
            if (bytes + size > MAX_BATCH_BYTES && batch.isNotEmpty()) {
                flushBatch()
            }
            batch.add(rule)
            bytes += size
        }
        flushBatch()
        GvLog.d(TAG, "import dispatched: $packageName rules=${rules.size}")
        return sent
    }

    /**
     * 按批下发缩略图：目标落盘后，规则管理列表就能显示原设备上那个控件的样子
     * ——本机布局不同、控件已被隐藏或压根不存在时，这是唯一能看到的图。
     */
    private fun sendThumbBatches(
        context: Context,
        packageName: String,
        payloads: List<ThumbPayload>,
    ): Boolean {
        if (payloads.isEmpty()) return false
        var sent = false
        var batch = ArrayList<ThumbPayload>()
        var bytes = 0

        fun flushBatch() {
            if (batch.isEmpty()) return
            val json = gson.toJson(batch)
            if (HostControlBridge.dispatchImportThumbs(
                    context.applicationContext,
                    packageName,
                    json,
                )
            ) {
                sent = true
            }
            batch = ArrayList()
            bytes = 0
        }

        for (payload in payloads) {
            val size = (payload.data?.length ?: 0) + (payload.key?.length ?: 0) + 16
            if (bytes + size > MAX_BATCH_BYTES && batch.isNotEmpty()) {
                flushBatch()
            }
            batch.add(payload)
            bytes += size
        }
        flushBatch()
        GvLog.d(TAG, "import thumbs dispatched: $packageName thumbs=${payloads.size}")
        return sent
    }
}
