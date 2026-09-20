package com.godviewer.app.target.rule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.ViewSnapshot
import com.godviewer.app.shared.mirror.mirrorThumbnailKey
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.shared.sha256
import java.io.File
import java.io.FileOutputStream

/**
 * 目标进程规则缩略图：内存缓存 + files/godviewer/thumbnails/ 落盘。
 *
 * 在 [ViewRuleManager.createRule] 时截取（视图仍可见）；列表与镜像推送只读缓存。
 * 第二阶段迁包时预期落入 `target`。
 */
internal object ViewRuleThumbnails {

    private const val TAG = "Thumbs"

    /** 待回推宿主的新缩略图（key → bitmap）。只在上层 replay / 创建规则的主线程里读写。 */
    private const val MAX_PENDING_THUMBS = 24

    /** 单张导入缩略图上限（远超 128px 图的实际体积，只是防异常文件）。 */
    private const val MAX_IMPORT_THUMB_BYTES = 512 * 1024

    private val THUMB_KEY_REGEX = Regex("^[a-f0-9]{8,64}$")

    private var appContext: Context? = null
    private val thumbnails = HashMap<ViewRule.RuleKey, Bitmap>()
    private val pendingNew = LinkedHashMap<String, Bitmap>()

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 规则对应的缩略图：内存缓存 → 磁盘文件 → null。
     */
    fun thumbnailFor(rule: ViewRule): Bitmap? {
        thumbnails[rule.key()]?.let { return it }
        val file = thumbnailFile(rule.key()) ?: return null
        if (!file.exists()) {
            return null
        }
        return runCatching {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                thumbnails[rule.key()] = bitmap
            }
            bitmap
        }.getOrNull()
    }

    /**
     * 截取并持久化缩略图（视图须已布局且当前可见）。已有缩略图时跳过。
     *
     * @return 本次是否新抓到缩略图（true 表示宿主镜像可能缺这张图，需要回推）
     */
    fun capture(view: View, rule: ViewRule): Boolean {
        if (thumbnailFor(rule) != null) {
            return false
        }
        if (view.visibility == View.GONE || !view.isLaidOut || view.width <= 0 || view.height <= 0) {
            return false
        }
        return runCatching {
            val bitmap = ViewSnapshot.capture(view, maxEdge = 256) ?: return@runCatching false
            thumbnails[rule.key()] = bitmap
            saveThumbnailToFile(rule.key(), bitmap)
            notePending(mirrorThumbnailKey(rule), bitmap)
            true
        }.getOrDefault(false)
    }

    /** 记录待回推的新缩略图；超出上限时丢掉最老的，避免常驻内存。 */
    private fun notePending(key: String, bitmap: Bitmap) {
        if (pendingNew.size >= MAX_PENDING_THUMBS) {
            val first = pendingNew.keys.firstOrNull() ?: return
            pendingNew.remove(first)
        }
        pendingNew[key] = bitmap
    }

    /** 待回推的新缩略图数量（用于限频判断，避免无谓的 drain）。 */
    fun pendingCount(): Int = pendingNew.size

    /**
     * 取出并清空待回推的新缩略图（调用方负责推送）。
     */
    fun drainPendingNew(): Map<String, Bitmap> {
        if (pendingNew.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Bitmap>(pendingNew)
        pendingNew.clear()
        return out
    }

    fun remove(rule: ViewRule) {
        val key = rule.key()
        thumbnails.remove(key)
        thumbnailFile(key)?.delete()
        pendingNew.remove(mirrorThumbnailKey(rule))
    }

    /**
     * 备份导入：把宿主下发的缩略图原始字节落盘，供规则管理列表显示。
     *
     * 本机已经实拍过的以本机为准（不覆盖）；内存缓存无需清理——
     * 缓存里的图必然已落盘，而落盘的图这里会跳过。
     *
     * @return 实际写入的张数
     */
    fun importThumbs(raw: Map<String, ByteArray>): Int {
        val dir = thumbnailDir ?: return 0
        if (!dir.exists() && !dir.mkdirs()) return 0
        var written = 0
        for ((key, bytes) in raw) {
            if (bytes.isEmpty() || bytes.size > MAX_IMPORT_THUMB_BYTES) continue
            if (!key.matches(THUMB_KEY_REGEX)) continue
            val file = File(dir, "$key.png")
            if (file.exists()) continue
            runCatching {
                file.writeBytes(bytes)
                written++
            }.onFailure {
                GvLog.w(TAG, "import thumb failed key=$key", it)
            }
        }
        return written
    }

    private val thumbnailDir: File?
        get() = appContext?.let { File(File(it.filesDir, "godviewer"), "thumbnails") }

    private fun thumbnailFile(key: ViewRule.RuleKey): File? {
        val dir = thumbnailDir ?: return null
        return File(dir, thumbnailName(key))
    }

    /** 规则键 → 文件名：键内容 SHA-256 前 16 位十六进制 + .png */
    private fun thumbnailName(key: ViewRule.RuleKey): String {
        val raw = "${key.activityClass}|${key.viewClass}|${key.depth.joinToString(",")}"
        return sha256(raw).take(16) + ".png"
    }

    private fun saveThumbnailToFile(key: ViewRule.RuleKey, bitmap: Bitmap) {
        val file = thumbnailFile(key) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }
}
