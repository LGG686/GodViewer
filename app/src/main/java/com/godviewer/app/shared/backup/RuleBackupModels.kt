package com.godviewer.app.shared.backup

import com.google.gson.annotations.SerializedName
import com.godviewer.app.shared.model.ViewRule

/**
 * 规则备份文件格式（host 侧导出 / 导入共用）。
 *
 * 备份只含规则及其缩略图，不含 GodViewer 设置项（入口模式、磁贴开关等），
 * 也不含应用图标——图标可从系统 PackageManager 现取。
 *
 * ## 容器
 *
 * 导出为一个 **zip**，缩略图按原始字节存条目（PNG / JPEG 原样，不再二次编码）：
 *
 * ```
 * manifest.json                              （放第一个，方便外部查看）
 * packages/<pkg>/rules.json
 * packages/<pkg>/thumbnails/<key>.png|.jpg   （STORED，不压缩）
 * ```
 *
 * 缩略图 key 与镜像目录里的文件名一致（规则内容的短哈希），导入时按同样的 key 落盘。
 * 早期（4.3.5 之前）导出的是单文件 JSON、缩略图内嵌 base64，导入侧仍能识别。
 */
object RuleBackupProtocol {
    const val FORMAT = "godviewer-backup"
    const val SCHEMA_VERSION = 1

    /** 容器类型：zip（当前）与裸 json（历史格式，只读）。 */
    const val CONTAINER_ZIP = "zip"
    const val CONTAINER_JSON = "json"

    /** zip 内条目布局。 */
    const val MANIFEST_ENTRY = "manifest.json"
    const val PACKAGES_DIR = "packages/"
    const val RULES_ENTRY = "rules.json"
    const val THUMBS_DIR = "thumbnails/"

    /** 导入文件大小上限，防止误选大文件把内存打满。 */
    const val MAX_FILE_BYTES = 64 * 1024 * 1024
    const val MAX_PACKAGES = 500
    const val MAX_RULES_PER_PACKAGE = 5000
    /** 单张缩略图字节上限（与镜像落盘解码上限一致）。 */
    const val MAX_THUMB_BYTES = 512 * 1024
    const val MAX_THUMB_KEY_LENGTH = 64
    /** zip 解包护栏：条目数、解压后总字节、单条目字节。 */
    const val MAX_ENTRIES = 6000
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 96 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1024 * 1024
    const val MAX_RULES_ENTRY_BYTES = 8 * 1024 * 1024

    const val MIME = "application/zip"
    const val EXTENSION = "zip"
}

data class BackupAppInfo(
    @SerializedName("version_name") val versionName: String? = null,
    @SerializedName("version_code") val versionCode: Int = 0,
)

// 说明：字段全部可空——Gson 走 Unsafe 实例化，遇到缺失字段会留下 null，
// 而不是 Kotlin 的默认值；读侧一律用 orEmpty() / 判空兜住被手改坏的备份文件。
data class BackupPackageEntry(
    @SerializedName("package_name") val packageName: String? = null,
    @SerializedName("app_label") val appLabel: String? = null,
    /** 仅裸 json 容器使用：规则内嵌在清单里。 */
    @SerializedName("rules") val rules: List<ViewRule>? = null,
    /** 仅裸 json 容器使用：缩略图 key → base64。 */
    @SerializedName("thumbnails") val thumbnails: Map<String, String>? = null,
    /** 仅 zip 容器的清单使用：条目计数，方便外部工具核对。 */
    @SerializedName("rule_count") val ruleCount: Int = 0,
    @SerializedName("thumbnail_count") val thumbnailCount: Int = 0,
)

data class RuleBackupFile(
    @SerializedName("format") val format: String? = null,
    @SerializedName("schema_version") val schemaVersion: Int = 0,
    @SerializedName("container") val container: String? = null,
    @SerializedName("exported_at") val exportedAt: Long = 0L,
    @SerializedName("app") val app: BackupAppInfo? = null,
    @SerializedName("packages") val packages: List<BackupPackageEntry>? = null,
)

/** zip 内 `packages/<pkg>/rules.json` 的结构。 */
data class BackupRulesEntry(
    @SerializedName("package_name") val packageName: String? = null,
    @SerializedName("app_label") val appLabel: String? = null,
    @SerializedName("rules") val rules: List<ViewRule>? = null,
)

/** 从备份里读出来的一个应用：规则 + 缩略图原始字节。 */
data class BackupPackageSource(
    val packageName: String,
    val appLabel: String?,
    val rules: List<ViewRule>,
    val thumbnails: Map<String, ByteArray>,
)

/** 读进内存的整份备份（zip 与裸 json 统一成这一种）。 */
data class RuleBackupSource(
    val container: String,
    val manifest: RuleBackupFile,
    val packages: List<BackupPackageSource>,
)

/**
 * 宿主 → 目标下发缩略图的一条（key → base64 图片）。
 *
 * 用数组而不是 map，是为了让分批时能按条累加字节、切批后仍是合法 JSON。
 * 字段可空：Gson 走 Unsafe 实例化，坏数据会留下 null。
 */
data class ThumbPayload(
    @SerializedName("key") val key: String? = null,
    @SerializedName("data") val data: String? = null,
)
