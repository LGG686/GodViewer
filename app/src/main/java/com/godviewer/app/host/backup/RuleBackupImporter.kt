package com.godviewer.app.host.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.backup.BackupPackageSource
import com.godviewer.app.shared.backup.BackupRulesEntry
import com.godviewer.app.shared.backup.RuleBackupFile
import com.godviewer.app.shared.backup.RuleBackupProtocol
import com.godviewer.app.shared.backup.RuleBackupSource
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.mirror.mirrorThumbnailKey
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import com.godviewer.app.shared.model.ViewRule
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** 单个应用在备份里的恢复预览。 */
data class PackagePreview(
    val packageName: String,
    val label: String,
    val added: Int,
    val replaced: Int,
    val unchanged: Int,
    val installed: Boolean,
    val invalid: Int,
    /**
     * 既没有资源名也没有文字的条数。
     *
     * 导入的规则在本机不信任 depth 位置，只认身份锚点；这类规则恢复后
     * 没有可靠办法定位控件，多半不会生效（宁可不生效，也不能误隐藏别的控件）。
     */
    val unlocatable: Int,
)

/** 整份备份的恢复预览。 */
data class ImportPreview(
    val packages: List<PackagePreview>,
) {
    val addedTotal: Int get() = packages.sumOf { it.added }
    val replacedTotal: Int get() = packages.sumOf { it.replaced }
    val invalidTotal: Int get() = packages.sumOf { it.invalid }
    val unlocatableTotal: Int get() = packages.sumOf { it.unlocatable }
    val uninstalled: Int get() = packages.count { !it.installed }
    val packageCount: Int get() = packages.size
}

data class ImportResult(
    val packages: Int,
    val rules: Int,
    val delivered: Int,
)

/**
 * 宿主进程：读取备份文件 → 预览 → 合并写入镜像 → 下发目标。
 *
 * 支持两种容器：
 * - **zip**（当前导出格式）：清单 + 每包 rules.json + 缩略图原始字节
 * - **裸 json**（4.3.5 之前的格式，缩略图内嵌 base64）：仍可读
 *
 * 合并策略：按规则键（activityClass + viewClass + depth）比对，
 * 同键取 `timestamp` 较新的那条；备份里没有的本地规则一律保留。
 */
internal object RuleBackupImporter {
    private const val TAG = "Backup"
    private const val PREFS = "godviewer_backup"
    private const val KEY_LAST_BATCH = "last_batch_id"
    private const val KEY_LAST_PACKAGES = "last_packages"
    private val gson: Gson = GsonBuilder().create()
    private val THUMB_KEY_REGEX = Regex("^[a-f0-9]{8,64}$")

    /** 读取并校验备份文件；不是 GodViewer 备份 / 版本过高 / 过大时返回 null。 */
    fun read(context: Context, uri: Uri): RuleBackupSource? {
        return runCatching {
            val isZip = probeZip(context, uri)
            if (isZip == null) {
                // 读不出任何字节，按 json 走一次，让校验逻辑给出「无效」
                readJson(context, uri)
            } else if (isZip) {
                readZip(context, uri)
            } else {
                readJson(context, uri)
            }
        }.getOrNull()
    }

    /** 嗅探文件头；空文件 / 读不到时返回 null。 */
    private fun probeZip(context: Context, uri: Uri): Boolean? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(4)
                val n = input.read(head)
                if (n < 4) return false
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                    head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
            }
        }.getOrNull()
    }

    private fun fileTooLarge(context: Context, uri: Uri): Boolean {
        val size = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: return false
        return size > RuleBackupProtocol.MAX_FILE_BYTES
    }

    // ---------- zip ----------

    private fun readZip(context: Context, uri: Uri): RuleBackupSource? {
        if (fileTooLarge(context, uri)) return null
        var manifest: RuleBackupFile? = null
        val rulesByPkg = LinkedHashMap<String, ByteArray>()
        val thumbsByPkg = LinkedHashMap<String, MutableMap<String, ByteArray>>()
        var budget = RuleBackupProtocol.MAX_TOTAL_UNCOMPRESSED_BYTES
        var entries = 0

        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (++entries > RuleBackupProtocol.MAX_ENTRIES) break
                    val name = entry.name ?: continue
                    // zip slip：拒绝绝对路径与 `..`
                    if (name.startsWith("/") || name.contains("..")) continue
                    when {
                        name == RuleBackupProtocol.MANIFEST_ENTRY -> {
                            val bytes = readEntry(zis, RuleBackupProtocol.MAX_MANIFEST_BYTES)
                                ?: continue
                            manifest = parseManifest(bytes)
                        }
                        name.startsWith(RuleBackupProtocol.PACKAGES_DIR) &&
                            name.endsWith("/" + RuleBackupProtocol.RULES_ENTRY) -> {
                            val pkg = name.removePrefix(RuleBackupProtocol.PACKAGES_DIR)
                                .removeSuffix("/" + RuleBackupProtocol.RULES_ENTRY)
                            if (pkg.isBlank() || pkg.contains("/")) continue
                            val bytes = readEntry(zis, RuleBackupProtocol.MAX_RULES_ENTRY_BYTES)
                                ?: continue
                            budget -= bytes.size
                            rulesByPkg[pkg] = bytes
                        }
                        name.startsWith(RuleBackupProtocol.PACKAGES_DIR) -> {
                            val marker = "/" + RuleBackupProtocol.THUMBS_DIR
                            val rest = name.removePrefix(RuleBackupProtocol.PACKAGES_DIR)
                            val idx = rest.indexOf(marker)
                            if (idx <= 0) continue
                            val pkg = rest.substring(0, idx)
                            val file = rest.substring(idx + marker.length)
                            if (pkg.contains("/") || file.contains("/")) continue
                            val key = file.substringBefore('.')
                            if (key.isBlank() || !key.matches(THUMB_KEY_REGEX)) continue
                            val bytes = readEntry(zis, RuleBackupProtocol.MAX_THUMB_BYTES)
                                ?: continue
                            if (bytes.size > budget) continue
                            budget -= bytes.size
                            thumbsByPkg.getOrPut(pkg) { LinkedHashMap() }[key] = bytes
                        }
                    }
                    if (budget <= 0) break
                }
            }
        }

        val manifestFile = manifest
        val declared = manifestFile?.packages.orEmpty()
        val ordered: List<String> = if (declared.isNotEmpty()) {
            declared.mapNotNull { it.packageName }
        } else {
            rulesByPkg.keys.toList()
        }
        val labels = declared.associateBy({ it.packageName }, { it.appLabel })

        val packages = ArrayList<BackupPackageSource>()
        for (raw in ordered.take(RuleBackupProtocol.MAX_PACKAGES)) {
            val pkg = sanitizeMirrorPackageName(raw) ?: continue
            val rulesBytes = rulesByPkg[pkg] ?: continue
            val rules = runCatching {
                gson.fromJson(String(rulesBytes, Charsets.UTF_8), BackupRulesEntry::class.java)
            }.getOrNull()?.rules.orEmpty()
            packages.add(
                BackupPackageSource(
                    packageName = pkg,
                    appLabel = labels[raw]?.takeIf { it.isNotBlank() },
                    rules = rules,
                    thumbnails = thumbsByPkg[pkg].orEmpty(),
                ),
            )
        }
        if (packages.isEmpty()) return null
        val resolved = manifestFile ?: RuleBackupFile(
            format = RuleBackupProtocol.FORMAT,
            schemaVersion = RuleBackupProtocol.SCHEMA_VERSION,
            container = RuleBackupProtocol.CONTAINER_ZIP,
            exportedAt = 0L,
            app = null,
            packages = packages.map {
                com.godviewer.app.shared.backup.BackupPackageEntry(
                    packageName = it.packageName,
                    appLabel = it.appLabel,
                    ruleCount = it.rules.size,
                    thumbnailCount = it.thumbnails.size,
                )
            },
        )
        return RuleBackupSource(RuleBackupProtocol.CONTAINER_ZIP, resolved, packages)
    }

    private fun parseManifest(bytes: ByteArray): RuleBackupFile? {
        val parsed = runCatching {
            gson.fromJson(String(bytes, Charsets.UTF_8), RuleBackupFile::class.java)
        }.getOrNull() ?: return null
        if (parsed.format != RuleBackupProtocol.FORMAT) return null
        val version = parsed.schemaVersion
        if (version <= 0 || version > RuleBackupProtocol.SCHEMA_VERSION) return null
        return parsed
    }

    /** 读当前条目，超过 [limit] 字节就丢弃（返回 null）。 */
    private fun readEntry(input: java.io.InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        var overflow = false
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) {
                overflow = true
                continue // 继续把这个条目读干净，交给下一个 nextEntry
            }
            out.write(buf, 0, n)
        }
        return if (overflow) null else out.toByteArray()
    }

    // ---------- 裸 json（历史格式） ----------

    private fun readJson(context: Context, uri: Uri): RuleBackupSource? {
        if (fileTooLarge(context, uri)) return null
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.isEmpty()) return null
        val parsed = parseManifest(bytes) ?: return null
        val packages = ArrayList<BackupPackageSource>()
        for (entry in parsed.packages.orEmpty().take(RuleBackupProtocol.MAX_PACKAGES)) {
            val pkg = sanitizeMirrorPackageName(entry.packageName) ?: continue
            val thumbs = LinkedHashMap<String, ByteArray>()
            entry.thumbnails.orEmpty().forEach { (key, b64) ->
                if (b64.isBlank() || !key.matches(THUMB_KEY_REGEX)) return@forEach
                if (b64.length > RuleBackupProtocol.MAX_THUMB_BYTES) return@forEach
                val raw = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (raw != null && raw.isNotEmpty()) {
                    thumbs[key] = raw
                }
            }
            packages.add(
                BackupPackageSource(
                    packageName = pkg,
                    appLabel = entry.appLabel,
                    rules = entry.rules.orEmpty(),
                    thumbnails = thumbs,
                ),
            )
        }
        if (packages.isEmpty()) return null
        return RuleBackupSource(RuleBackupProtocol.CONTAINER_JSON, parsed, packages)
    }

    // ---------- 预览 / 应用 ----------

    fun preview(context: Context, source: RuleBackupSource): ImportPreview {
        val previews = ArrayList<PackagePreview>()
        for (entry in source.packages) {
            val pkg = entry.packageName
            val valid = validRules(pkg, entry.rules)
            val invalid = entry.rules.size - valid.size
            val current = RuleMirror.loadRules(context, pkg).associateBy { it.key() }
            var added = 0
            var replaced = 0
            var unchanged = 0
            var unlocatable = 0
            for (rule in valid) {
                val existing = current[rule.key()]
                when {
                    existing == null -> added++
                    rule.timestamp >= existing.timestamp -> replaced++
                    else -> unchanged++
                }
                if (!isLocatable(rule)) unlocatable++
            }
            if (added == 0 && replaced == 0 && invalid == 0) continue
            previews.add(
                PackagePreview(
                    packageName = pkg,
                    label = entry.appLabel?.takeIf { it.isNotBlank() } ?: pkg,
                    added = added,
                    replaced = replaced,
                    unchanged = unchanged,
                    installed = isInstalled(context, pkg),
                    invalid = invalid,
                    unlocatable = unlocatable,
                ),
            )
        }
        return ImportPreview(previews)
    }

    /** 应用备份：合并写镜像 + 缩略图，并尝试下发到目标进程。 */
    fun apply(context: Context, source: RuleBackupSource): ImportResult {
        val app = context.applicationContext
        val batchId = newBatchId()
        var packages = 0
        var ruleCount = 0
        var delivered = 0
        val touched = LinkedHashSet<String>()
        for (entry in source.packages) {
            val pkg = entry.packageName
            val incoming = validRules(pkg, entry.rules).map { rule ->
                // 备份里来的规则在本机一律按「外来」处理：匹配时只认身份锚点
                rule.imported = true
                rule.batchId = batchId
                rule
            }
            if (incoming.isEmpty()) continue
            val merged = mergeRules(RuleMirror.loadRules(app, pkg), incoming)
            val ok = RuleMirror.importPackage(
                app,
                pkg,
                merged,
                entry.thumbnails,
                entry.appLabel,
            )
            if (!ok) continue
            packages++
            ruleCount += incoming.size
            touched.add(pkg)
            // 只下发本次导入的部分（不是合并后的全部）——否则本机原有规则
            // 也会被一并打上「外来」标记，撤销时还会被误删
            RuleBackupDelivery.deliver(app, pkg, incoming, entry.thumbnails, batchId)
            delivered++
        }
        if (touched.isNotEmpty()) {
            saveLastImport(app, batchId, touched)
        }
        return ImportResult(packages, ruleCount, delivered)
    }

    // ---------- 撤销上次导入 ----------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saveLastImport(context: Context, batchId: String, packages: Set<String>) {
        prefs(context).edit()
            .putString(KEY_LAST_BATCH, batchId)
            .putStringSet(KEY_LAST_PACKAGES, packages)
            .apply()
    }

    private fun clearLastImport(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_BATCH)
            .remove(KEY_LAST_PACKAGES)
            .apply()
    }

    /** 是否有可撤销的导入（「撤销上次导入」按钮的可见性判断）。 */
    fun hasLastImport(context: Context): Boolean {
        return !prefs(context).getString(KEY_LAST_BATCH, null).isNullOrBlank()
    }

    /**
     * 撤销上一次导入：宿主重写镜像（移除该批次规则及其缩略图），
     * 并通知目标进程整批移除。
     *
     * 目标没运行时广播会丢，但宿主镜像已改，目标下次启动也不会有这批规则；
     * 目标下次上报前台时也不会再补发 pending（pending 一并清掉）。
     *
     * @return 移除的规则条数
     */
    fun undoLast(context: Context): Int {
        val app = context.applicationContext
        val batchId = prefs(app).getString(KEY_LAST_BATCH, null)
        if (batchId.isNullOrBlank()) return 0
        val pkgs = prefs(app).getStringSet(KEY_LAST_PACKAGES, null).orEmpty()
        var removed = 0
        for (pkg in pkgs) {
            val current = RuleMirror.loadRules(app, pkg)
            val batch = current.filter { it.batchId == batchId }
            if (batch.isEmpty()) continue
            val keeping = current.filterNot { it.batchId == batchId }
            if (keeping.isEmpty()) {
                RuleMirror.deletePackage(app, pkg)
            } else {
                RuleMirror.importPackage(app, pkg, keeping, emptyMap(), null)
                RuleMirror.deleteThumbnails(app, pkg, batch.map { mirrorThumbnailKey(it) })
            }
            removed += batch.size
            // pending 里可能还留着这批规则，撤销后不该再补发
            RuleBackupDelivery.clearPendingFor(app, pkg, batchId)
            HostControlBridge.dispatchUndoImport(app, pkg, batchId)
        }
        clearLastImport(app)
        GvLog.i(TAG, "last import undone: batch=$batchId removed=$removed")
        return removed
    }

    /** 导入的规则能否在本机可靠定位：至少要有资源名或文字其中一个身份锚点。 */
    private fun isLocatable(rule: ViewRule): Boolean =
        !rule.resourceName.isNullOrBlank() || !rule.text.isNullOrBlank()

    private fun newBatchId(): String =
        UUID.randomUUID().toString().substring(0, 8)

    /** 本地规则 + 备份规则合并，同键取 timestamp 新的。 */
    private fun mergeRules(current: List<ViewRule>, incoming: List<ViewRule>): List<ViewRule> {
        val merged = current.toMutableList()
        for (rule in incoming) {
            val index = merged.indexOfFirst { it.key() == rule.key() }
            if (index < 0) {
                merged.add(rule)
            } else if (rule.timestamp >= merged[index].timestamp) {
                merged[index] = rule
            }
        }
        return merged
    }

    /** 逐条字段校验：定位三要素缺一不可，包名必须与该应用一致。 */
    private fun validRules(pkg: String, rules: List<ViewRule>): List<ViewRule> {
        val out = ArrayList<ViewRule>()
        for (rule in rules.take(RuleBackupProtocol.MAX_RULES_PER_PACKAGE)) {
            if (rule.packageName != pkg) continue
            if (rule.activityClass.isBlank() || rule.viewClass.isBlank()) continue
            if (rule.depth.isEmpty()) continue
            out.add(rule)
        }
        return out
    }

    private fun isInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }
}
