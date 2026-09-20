package com.godviewer.app.host.backup

import android.content.Context
import android.net.Uri
import com.godviewer.app.BuildConfig
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.shared.backup.BackupAppInfo
import com.godviewer.app.shared.backup.BackupPackageEntry
import com.godviewer.app.shared.backup.BackupRulesEntry
import com.godviewer.app.shared.backup.RuleBackupFile
import com.godviewer.app.shared.backup.RuleBackupProtocol
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 宿主进程：把规则镜像导出成一份 **zip** 备份。
 *
 * ```
 * manifest.json                              清单（放第一个，方便外部直接查看）
 * packages/<pkg>/rules.json                  该应用的规则
 * packages/<pkg>/thumbnails/<key>.png|.jpg   原始字节，STORED 不再二次压缩
 * ```
 *
 * 数据来源是本体镜像（目标应用推送过来的 best-effort 副本），
 * 缩略图按镜像里已落盘的文件原样打包（缺失的留给目标下次回放补）。
 */
internal object RuleBackupExporter {
    private val gson: Gson = GsonBuilder().create()

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "godviewer-rules-$stamp.${RuleBackupProtocol.EXTENSION}"
    }

    /** 把备份写到用户选择的 SAF 位置。 */
    fun writeTo(context: Context, uri: Uri): Boolean {
        return runCatching {
            // 先扫一遍拿到计数（只列目录，不读字节），这样 manifest 能写在最前面
            val planned = ArrayList<PlannedPackage>()
            for (pkg in RuleMirror.listPackages(context)) {
                val safePkg = RuleMirror.sanitizePackageName(pkg.packageName) ?: continue
                val rules = RuleMirror.loadRules(context, safePkg)
                if (rules.isEmpty()) continue
                val thumbs = RuleMirror.exportThumbnails(context, safePkg)
                planned.add(PlannedPackage(safePkg, pkg.label, rules, thumbs))
            }

            val manifest = RuleBackupFile(
                format = RuleBackupProtocol.FORMAT,
                schemaVersion = RuleBackupProtocol.SCHEMA_VERSION,
                container = RuleBackupProtocol.CONTAINER_ZIP,
                exportedAt = System.currentTimeMillis(),
                app = BackupAppInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                packages = planned.map {
                    BackupPackageEntry(
                        packageName = it.packageName,
                        appLabel = it.label,
                        ruleCount = it.rules.size,
                        thumbnailCount = it.thumbs.size,
                    )
                },
            )

            context.contentResolver.openOutputStream(uri)?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    putEntry(
                        zip,
                        RuleBackupProtocol.MANIFEST_ENTRY,
                        gson.toJson(manifest).toByteArray(Charsets.UTF_8),
                        stored = false,
                    )
                    for (item in planned) {
                        val rulesJson = gson.toJson(
                            BackupRulesEntry(item.packageName, item.label, item.rules),
                        )
                        putEntry(
                            zip,
                            "${RuleBackupProtocol.PACKAGES_DIR}${item.packageName}/" +
                                RuleBackupProtocol.RULES_ENTRY,
                            rulesJson.toByteArray(Charsets.UTF_8),
                            stored = false,
                        )
                        for ((key, file) in item.thumbs) {
                            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                            if (bytes.isEmpty() || bytes.size > RuleBackupProtocol.MAX_THUMB_BYTES) {
                                continue
                            }
                            putEntry(
                                zip,
                                "${RuleBackupProtocol.PACKAGES_DIR}${item.packageName}/" +
                                    "${RuleBackupProtocol.THUMBS_DIR}$key.${extensionOf(bytes)}",
                                bytes,
                                // 缩略图本身已是 PNG/JPEG，再压一次几乎没有收益
                                stored = true,
                            )
                        }
                    }
                }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean) {
        val entry = ZipEntry(name)
        if (stored) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            val crc = CRC32()
            crc.update(bytes, 0, bytes.size)
            entry.crc = crc.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** 按文件头判断扩展名：镜像里既有老的 PNG 也有新的 JPEG。 */
    private fun extensionOf(bytes: ByteArray): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "png"
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return "jpg"
        }
        return "bin"
    }

    private class PlannedPackage(
        val packageName: String,
        val label: String,
        val rules: List<com.godviewer.app.shared.model.ViewRule>,
        val thumbs: Map<String, File>,
    )
}
