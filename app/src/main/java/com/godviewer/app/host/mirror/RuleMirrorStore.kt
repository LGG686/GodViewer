package com.godviewer.app.host.mirror

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.mirror.MirrorFile
import com.godviewer.app.shared.mirror.MirroredPackage
import com.godviewer.app.shared.mirror.RuleMirrorCodec
import com.godviewer.app.shared.mirror.RuleMirrorProtocol
import com.godviewer.app.shared.mirror.mirrorThumbnailKey
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import com.godviewer.app.shared.model.ViewRule
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream

/**
 * 宿主进程：接收镜像 JSON 后落盘，并提供列表/详情读取。
 *
 * 第二阶段迁包时预期落入 `host` 侧。
 */
internal object RuleMirrorStore {
    private const val TAG = "Mirror"
    private val gson: Gson = GsonBuilder().create()
    private val THUMB_KEY_REGEX = Regex("^[a-f0-9]{8,64}$")

    fun writeMirror(context: Context, packageName: String, json: String): Boolean {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return false
        if (json.isBlank() || json.length > 2 * 1024 * 1024) {
            return false
        }
        val parsed = runCatching {
            gson.fromJson(json, MirrorFile::class.java)
        }.getOrNull() ?: return false
        val pkg = sanitizeMirrorPackageName(parsed.packageName ?: safePkg) ?: return false
        val rules = parsed.rules ?: emptyList()

        return runCatching {
            val dir = packageDir(context, pkg)
            if (rules.isEmpty()) {
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
                GvLog.d(TAG, "mirror cleared: $pkg")
                return@runCatching true
            }
            if (!dir.exists()) {
                dir.mkdirs()
            }

            // Persist JSON without heavy base64 blobs (blobs go to files)
            val stored = MirrorFile(
                schemaVersion = 1,
                packageName = pkg,
                appLabel = parsed.appLabel?.takeIf { it.isNotBlank() },
                updatedAt = if (parsed.updatedAt > 0L) parsed.updatedAt else System.currentTimeMillis(),
                appIconPngBase64 = null,
                thumbnails = null,
                rules = rules,
            )
            val file = File(dir, RuleMirrorProtocol.RULES_FILE)
            val tmp = File(dir, "${RuleMirrorProtocol.RULES_FILE}.tmp")
            val outJson = gson.toJson(stored)
            FileOutputStream(tmp).use { out ->
                out.write(outJson.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }

            RuleMirrorCodec.decodeBase64ToFile(
                parsed.appIconPngBase64,
                File(dir, RuleMirrorProtocol.ICON_FILE),
            )

            val thumbDir = File(dir, RuleMirrorProtocol.THUMB_DIR)
            if (!parsed.thumbnails.isNullOrEmpty()) {
                if (!thumbDir.exists()) thumbDir.mkdirs()
                val keep = parsed.thumbnails.keys
                thumbDir.listFiles()?.forEach { f ->
                    val name = f.name.removeSuffix(".png")
                    if (name !in keep) f.delete()
                }
                parsed.thumbnails.forEach { (key, b64) ->
                    if (key.matches(Regex("^[a-f0-9]{8,64}$"))) {
                        RuleMirrorCodec.decodeBase64ToFile(b64, File(thumbDir, "$key.png"))
                    }
                }
            }

            GvLog.d(TAG, "mirror written: $pkg rules=${rules.size} label=${stored.appLabel}")
            true
        }.getOrDefault(false)
    }

    /**
     * 只写缩略图的独立批次（[RuleMirrorProtocol.ACTION_MIRROR_THUMBS]）。
     *
     * - merge：只写 / 覆盖，不清删已有文件（分批推送用）
     * - replace：清删不在本批 key 集合内的旧文件；value 留空表示「只声明保留、不写文件」
     *   （删除规则后目标回推的全量 key 列表走这条）
     *
     * 该包还没有 rules.json 时忽略，避免为未同步的包建出孤儿目录。
     */
    fun writeThumbBatch(context: Context, packageName: String, json: String): Boolean {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return false
        if (json.isBlank() || json.length > 2 * 1024 * 1024) {
            return false
        }
        val parsed = runCatching {
            gson.fromJson(json, MirrorFile::class.java)
        }.getOrNull() ?: return false
        val pkg = sanitizeMirrorPackageName(parsed.packageName ?: safePkg) ?: return false
        val thumbs = parsed.thumbnails
        if (thumbs.isNullOrEmpty()) {
            return false
        }
        return runCatching {
            val dir = packageDir(context, pkg)
            if (!File(dir, RuleMirrorProtocol.RULES_FILE).exists()) {
                GvLog.d(TAG, "thumb batch ignored: no rules yet for $pkg")
                return@runCatching false
            }
            val thumbDir = File(dir, RuleMirrorProtocol.THUMB_DIR)
            if (!thumbDir.exists()) thumbDir.mkdirs()
            if (parsed.thumbnailsMode == RuleMirrorProtocol.THUMB_MODE_REPLACE) {
                val keep = thumbs.keys
                thumbDir.listFiles()?.forEach { f ->
                    val name = f.name.removeSuffix(".png")
                    if (name !in keep) f.delete()
                }
            }
            var written = 0
            thumbs.forEach { (key, b64) ->
                if (b64.isBlank()) return@forEach
                if (key.matches(THUMB_KEY_REGEX)) {
                    RuleMirrorCodec.decodeBase64ToFile(b64, File(thumbDir, "$key.png"))
                    written++
                }
            }
            GvLog.d(
                TAG,
                "thumb batch: $pkg mode=${parsed.thumbnailsMode} " +
                    "keys=${thumbs.size} written=$written",
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 管理操作写回镜像：整份覆盖规则列表，顺带清掉不再有对应规则的孤儿缩略图。
     *
     * 这是**乐观更新**——目标可能还没收到指令。收敛靠两条路：目标在线会立刻重推自己的
     * 真相；目标离线则靠 pending 指令在下一次前台补发。用户此刻看到的是自己刚做的事，
     * 比看着列表纹丝不动却悄悄待生效要诚实。
     */
    fun saveManagedRules(context: Context, packageName: String, rules: List<ViewRule>): Boolean {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return false
        if (rules.isEmpty()) {
            return deletePackage(context, safePkg)
        }
        return runCatching {
            val dir = packageDir(context, safePkg)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val rulesFile = File(dir, RuleMirrorProtocol.RULES_FILE)
            val existingLabel = runCatching {
                gson.fromJson(rulesFile.readText(), MirrorFile::class.java)
            }.getOrNull()?.appLabel
            val stored = MirrorFile(
                schemaVersion = 1,
                packageName = safePkg,
                appLabel = existingLabel?.takeIf { it.isNotBlank() },
                updatedAt = System.currentTimeMillis(),
                appIconPngBase64 = null,
                thumbnails = null,
                rules = rules,
            )
            val tmp = File(dir, "${RuleMirrorProtocol.RULES_FILE}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(gson.toJson(stored).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(rulesFile)) {
                tmp.copyTo(rulesFile, overwrite = true)
                tmp.delete()
            }
            val keep = rules.map { mirrorThumbnailKey(it) }.toSet()
            val thumbDir = File(dir, RuleMirrorProtocol.THUMB_DIR)
            thumbDir.listFiles()?.forEach { f ->
                if (f.name.removeSuffix(".png") !in keep) f.delete()
            }
            GvLog.d(TAG, "mirror rewritten: $safePkg rules=${rules.size}")
            true
        }.getOrDefault(false)
    }

    /** 某包镜像里已落盘的缩略图（key → 文件），供备份导出读取。 */
    /** 删除指定 key 的镜像缩略图（撤销导入 / 删除规则时清孤儿用）。 */
    fun deleteThumbnails(context: Context, packageName: String, keys: Collection<String>): Int {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return 0
        val dir = File(packageDir(context, safePkg), RuleMirrorProtocol.THUMB_DIR)
        if (!dir.exists()) return 0
        var removed = 0
        for (key in keys) {
            if (!key.matches(THUMB_KEY_REGEX)) continue
            val file = File(dir, "$key.png")
            if (file.exists() && file.delete()) removed++
        }
        return removed
    }

    /** 删除整个包的镜像（规则 + 缩略图 + 图标）。 */
    fun deletePackage(context: Context, packageName: String): Boolean {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return false
        return runCatching { packageDir(context, safePkg).deleteRecursively() }
            .getOrDefault(false)
    }

    fun exportThumbnails(context: Context, packageName: String): Map<String, File> {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return emptyMap()
        val thumbDir = File(packageDir(context, safePkg), RuleMirrorProtocol.THUMB_DIR)
        val out = LinkedHashMap<String, File>()
        thumbDir.listFiles()?.forEach { f ->
            val key = f.name.removeSuffix(".png")
            if (key.matches(THUMB_KEY_REGEX)) {
                out[key] = f
            }
        }
        return out
    }

    /**
     * 备份导入：写该包的规则 + 缩略图。
     *
     * [rules] 由调用方合并完毕（同键取新）；缩略图只写 / 覆盖，不清删已有文件。
     */
    fun importPackage(
        context: Context,
        packageName: String,
        rules: List<ViewRule>,
        thumbnails: Map<String, ByteArray>,
        appLabel: String?,
    ): Boolean {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return false
        if (rules.isEmpty()) {
            return false
        }
        return runCatching {
            val dir = packageDir(context, safePkg)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val rulesFile = File(dir, RuleMirrorProtocol.RULES_FILE)
            val existingLabel = runCatching {
                gson.fromJson(rulesFile.readText(), MirrorFile::class.java)
            }.getOrNull()?.appLabel
            val stored = MirrorFile(
                schemaVersion = 1,
                packageName = safePkg,
                appLabel = appLabel?.takeIf { it.isNotBlank() }
                    ?: existingLabel?.takeIf { it.isNotBlank() },
                updatedAt = System.currentTimeMillis(),
                appIconPngBase64 = null,
                thumbnails = null,
                rules = rules,
            )
            val tmp = File(dir, "${RuleMirrorProtocol.RULES_FILE}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(gson.toJson(stored).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(rulesFile)) {
                tmp.copyTo(rulesFile, overwrite = true)
                tmp.delete()
            }
            if (thumbnails.isNotEmpty()) {
                val thumbDir = File(dir, RuleMirrorProtocol.THUMB_DIR)
                if (!thumbDir.exists()) thumbDir.mkdirs()
                thumbnails.forEach { (key, bytes) ->
                    if (bytes.isNotEmpty() && key.matches(THUMB_KEY_REGEX)) {
                        writeThumbFile(File(thumbDir, "$key.png"), bytes)
                    }
                }
            }
            GvLog.d(
                TAG,
                "import written: $safePkg rules=${rules.size} thumbs=${thumbnails.size}",
            )
            true
        }.getOrDefault(false)
    }

    /** 缩略图原样落盘（tmp + rename，避免半截文件）。 */
    private fun writeThumbFile(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun listPackages(context: Context): List<MirroredPackage> {
        val root = mirrorRoot(context)
        if (!root.exists()) {
            return emptyList()
        }
        val pm = context.packageManager
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val pkg = sanitizeMirrorPackageName(dir.name) ?: return@mapNotNull null
                val file = File(dir, RuleMirrorProtocol.RULES_FILE)
                if (!file.exists()) {
                    return@mapNotNull null
                }
                val mirror = runCatching {
                    gson.fromJson(file.readText(), MirrorFile::class.java)
                }.getOrNull()
                val rules = mirror?.rules.orEmpty()
                if (rules.isEmpty()) {
                    return@mapNotNull null
                }
                val pmLabel = runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(ai).toString()
                }.getOrNull()
                val label = pmLabel
                    ?: mirror?.appLabel?.takeIf { it.isNotBlank() }
                    ?: pkg
                val pmIcon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                val fileIcon = loadIconDrawable(context, dir)
                val icon = pmIcon ?: fileIcon
                val isSystem = runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                }.getOrDefault(false)
                MirroredPackage(
                    packageName = pkg,
                    label = label,
                    ruleCount = rules.size,
                    updatedAt = mirror?.updatedAt
                        ?: rules.maxOfOrNull { it.timestamp }
                        ?: file.lastModified(),
                    icon = icon,
                    isSystem = isSystem,
                )
            }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()
    }

    fun loadRules(context: Context, packageName: String): List<ViewRule> {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return emptyList()
        val file = File(packageDir(context, safePkg), RuleMirrorProtocol.RULES_FILE)
        if (!file.exists()) {
            return emptyList()
        }
        return runCatching {
            gson.fromJson(file.readText(), MirrorFile::class.java)?.rules.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun loadAppLabel(context: Context, packageName: String): String {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return packageName
        val fromPm = runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(safePkg, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrNull()
        if (!fromPm.isNullOrBlank()) return fromPm
        val file = File(packageDir(context, safePkg), RuleMirrorProtocol.RULES_FILE)
        if (file.exists()) {
            val mirror = runCatching {
                gson.fromJson(file.readText(), MirrorFile::class.java)
            }.getOrNull()
            mirror?.appLabel?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return safePkg
    }

    fun loadAppIcon(context: Context, packageName: String): Drawable? {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return null
        runCatching {
            return context.packageManager.getApplicationIcon(safePkg)
        }
        return loadIconDrawable(context, packageDir(context, safePkg))
    }

    fun loadThumbnail(context: Context, packageName: String, rule: ViewRule): Bitmap? {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return null
        val key = mirrorThumbnailKey(rule)
        val file = File(
            File(packageDir(context, safePkg), RuleMirrorProtocol.THUMB_DIR),
            "$key.png",
        )
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun packageCount(context: Context): Int = listPackages(context).size

    private fun mirrorRoot(context: Context): File =
        File(context.applicationContext.filesDir, RuleMirrorProtocol.MIRROR_DIR)

    private fun packageDir(context: Context, packageName: String): File =
        File(mirrorRoot(context), packageName)

    private fun loadIconDrawable(context: Context, dir: File): Drawable? {
        val file = File(dir, RuleMirrorProtocol.ICON_FILE)
        if (!file.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        return BitmapDrawable(context.resources, bmp)
    }
}
