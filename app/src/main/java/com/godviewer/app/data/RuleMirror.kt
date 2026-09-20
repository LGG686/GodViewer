package com.godviewer.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.godviewer.app.shared.mirror.MirrorFile
import com.godviewer.app.shared.mirror.MirroredPackage
import com.godviewer.app.shared.mirror.RuleMirrorProtocol
import com.godviewer.app.shared.mirror.mirrorThumbnailKey
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import com.godviewer.app.shared.model.ViewRule

/**
 * 规则镜像对外薄 facade（保持历史 call site：`RuleMirror.*`）。
 *
 * 实现已按职责拆到：
 * - [RuleMirrorProtocol] / [MirrorFile] / [MirroredPackage] — 协议与 DTO
 * - target [com.godviewer.app.target.mirror.RuleMirrorPush] — 目标进程推送
 * - host [com.godviewer.app.host.mirror.RuleMirrorStore] — 宿主读写
 *
 * 方法体内再引用 host/target 实现类，避免本 facade 类加载时
 * 在目标进程过早解析宿主 UI/Store 依赖图。
 *
 * 权威规则仍在各目标应用私有 RuleStore。
 */
object RuleMirror {
    const val ACTION_MIRROR_RULES = RuleMirrorProtocol.ACTION_MIRROR_RULES
    const val ACTION_MIRROR_THUMBS = RuleMirrorProtocol.ACTION_MIRROR_THUMBS
    const val EXTRA_PACKAGE = RuleMirrorProtocol.EXTRA_PACKAGE
    const val EXTRA_JSON = RuleMirrorProtocol.EXTRA_JSON
    const val EXTRA_TOKEN = RuleMirrorProtocol.EXTRA_TOKEN
    const val MIRROR_TOKEN = RuleMirrorProtocol.MIRROR_TOKEN

    fun pushFromTarget(context: Context, packageName: String, rules: List<ViewRule>) {
        com.godviewer.app.target.mirror.RuleMirrorPush.pushFromTarget(context, packageName, rules)
    }

    fun writeMirror(context: Context, packageName: String, json: String): Boolean {
        return com.godviewer.app.host.mirror.RuleMirrorStore.writeMirror(context, packageName, json)
    }

    /** 宿主侧：写缩略图批次（merge / replace）。 */
    fun writeThumbBatch(context: Context, packageName: String, json: String): Boolean {
        return com.godviewer.app.host.mirror.RuleMirrorStore.writeThumbBatch(
            context,
            packageName,
            json,
        )
    }

    /** 宿主侧：某包镜像里已存在的缩略图（key → 文件），供备份导出使用。 */
    fun exportThumbnails(context: Context, packageName: String): Map<String, java.io.File> {
        return com.godviewer.app.host.mirror.RuleMirrorStore.exportThumbnails(context, packageName)
    }

    /** 宿主侧：导入时写入规则 + 缩略图原始字节（合并语义，不删已有图）。 */
    fun importPackage(
        context: Context,
        packageName: String,
        rules: List<ViewRule>,
        thumbnails: Map<String, ByteArray>,
        appLabel: String?,
    ): Boolean {
        return com.godviewer.app.host.mirror.RuleMirrorStore.importPackage(
            context,
            packageName,
            rules,
            thumbnails,
            appLabel,
        )
    }

    /** 宿主侧：删除指定 key 的镜像缩略图。 */
    fun deleteThumbnails(context: Context, packageName: String, keys: Collection<String>): Int {
        return com.godviewer.app.host.mirror.RuleMirrorStore.deleteThumbnails(
            context,
            packageName,
            keys,
        )
    }

    /** 宿主侧：删除整个包的镜像。 */
    fun deletePackage(context: Context, packageName: String): Boolean {
        return com.godviewer.app.host.mirror.RuleMirrorStore.deletePackage(context, packageName)
    }

    fun listPackages(context: Context): List<MirroredPackage> {
        return com.godviewer.app.host.mirror.RuleMirrorStore.listPackages(context)
    }

    fun loadRules(context: Context, packageName: String): List<ViewRule> {
        return com.godviewer.app.host.mirror.RuleMirrorStore.loadRules(context, packageName)
    }

    fun loadAppLabel(context: Context, packageName: String): String {
        return com.godviewer.app.host.mirror.RuleMirrorStore.loadAppLabel(context, packageName)
    }

    fun loadAppIcon(context: Context, packageName: String): Drawable? {
        return com.godviewer.app.host.mirror.RuleMirrorStore.loadAppIcon(context, packageName)
    }

    fun loadThumbnail(context: Context, packageName: String, rule: ViewRule): Bitmap? {
        return com.godviewer.app.host.mirror.RuleMirrorStore.loadThumbnail(context, packageName, rule)
    }

    fun packageCount(context: Context): Int {
        return com.godviewer.app.host.mirror.RuleMirrorStore.packageCount(context)
    }

    fun thumbnailKey(rule: ViewRule): String = mirrorThumbnailKey(rule)

    fun sanitizePackageName(raw: String?): String? = sanitizeMirrorPackageName(raw)
}
