package com.godviewer.app.target.mirror

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.godviewer.app.BuildConfig
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.mirror.MirrorFile
import com.godviewer.app.shared.mirror.RuleMirrorCodec
import com.godviewer.app.shared.mirror.RuleMirrorProtocol
import com.godviewer.app.shared.mirror.mirrorThumbnailKey
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.shared.resolveAppLabel
import com.godviewer.app.target.rule.RuleStore
import com.godviewer.app.target.rule.ViewRuleManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * 目标进程：本地 [RuleStore.save] 成功后 best-effort 推送镜像广播。
 *
 * 推送分两步：
 * 1. [ACTION_MIRROR_RULES] — 只带规则（+ 应用图标），不含 base64 缩略图，
 *    规则条数不再被图片挤占；
 * 2. [ACTION_MIRROR_THUMBS] — 缩略图按 [RuleMirrorProtocol.MAX_THUMB_BATCH_BYTES]
 *    拆成多批补推（merge 语义），不再「装不下就丢弃」。
 *
 * 第二阶段迁包时预期落入 `target`（inject）侧。
 */
internal object RuleMirrorPush {
    private const val TAG = "Mirror"
    private val gson: Gson = GsonBuilder().create()

    /** 缩略图 JPEG 质量：128px 下肉眼无损，体积约为 PNG 的 1/4。 */
    private const val THUMB_JPEG_QUALITY = 85

    /**
     * Best-effort sync from the injected target process. Never throws into the target app.
     */
    fun pushFromTarget(context: Context, packageName: String, rules: List<ViewRule>) {
        runCatching {
            val safePkg = sanitizeMirrorPackageName(packageName) ?: return
            val app = context.applicationContext
            val label = resolveAppLabel(app, safePkg)
            val iconB64 = encodeAppIcon(app, safePkg)

            var payload = MirrorFile(
                schemaVersion = 1,
                packageName = safePkg,
                appLabel = label,
                updatedAt = System.currentTimeMillis(),
                appIconPngBase64 = iconB64,
                rules = rules,
            )
            var json = gson.toJson(payload)
            // 规则批次不夹带缩略图，只剩图标这一个可选大字段
            if (json.toByteArray(Charsets.UTF_8).size > RuleMirrorProtocol.MAX_JSON_BYTES &&
                !iconB64.isNullOrBlank()
            ) {
                payload = payload.copy(appIconPngBase64 = null)
                json = gson.toJson(payload)
            }
            if (json.toByteArray(Charsets.UTF_8).size > RuleMirrorProtocol.MAX_JSON_BYTES) {
                GvLog.w(TAG, "mirror json still too large (${json.length}), skip push")
                return
            }

            val intent = Intent(RuleMirrorProtocol.ACTION_MIRROR_RULES).apply {
                component = ComponentName(
                    BuildConfig.PACKAGE_NAME,
                    RuleMirrorProtocol.RECEIVER_CLASS,
                )
                putExtra(RuleMirrorProtocol.EXTRA_PACKAGE, safePkg)
                putExtra(RuleMirrorProtocol.EXTRA_JSON, json)
                putExtra(RuleMirrorProtocol.EXTRA_TOKEN, RuleMirrorProtocol.MIRROR_TOKEN)
            }
            app.sendBroadcast(intent)
            GvLog.d(TAG, "mirror push sent: $safePkg rules=${rules.size} label=$label")

            // 缩略图另走独立批次：分批补推，本体镜像会随使用越来越完整
            if (rules.isNotEmpty()) {
                pushThumbBatches(app, safePkg, rules)
            }
        }.onFailure {
            GvLog.w(TAG, "mirror push failed", it)
        }
    }

    /**
     * 全量缩略图分批推送（merge）：每条规则有图就推，不再因单批上限截断丢弃。
     */
    fun pushThumbBatches(context: Context, packageName: String, rules: List<ViewRule>) {
        runCatching {
            val safePkg = sanitizeMirrorPackageName(packageName) ?: return
            val app = context.applicationContext
            var batch = LinkedHashMap<String, String>()
            var bytes = 0
            for (rule in rules) {
                val bmp = ViewRuleManager.thumbnailFor(rule) ?: continue
                val b64 = runCatching {
                    RuleMirrorCodec.bitmapToBase64Jpeg(bmp, THUMB_JPEG_QUALITY)
                }.getOrNull() ?: continue
                if (b64.length > RuleMirrorProtocol.MAX_SINGLE_THUMB_BYTES) continue
                if (bytes + b64.length > RuleMirrorProtocol.MAX_THUMB_BATCH_BYTES &&
                    batch.isNotEmpty()
                ) {
                    sendThumbBatch(app, safePkg, batch, RuleMirrorProtocol.THUMB_MODE_MERGE)
                    batch = LinkedHashMap()
                    bytes = 0
                }
                batch[mirrorThumbnailKey(rule)] = b64
                bytes += b64.length
            }
            if (batch.isNotEmpty()) {
                sendThumbBatch(app, safePkg, batch, RuleMirrorProtocol.THUMB_MODE_MERGE)
            }
        }.onFailure {
            GvLog.w(TAG, "push thumb batches failed", it)
        }
    }

    /**
     * 只推 key 集合（值留空）：宿主据此清掉不在集合内的孤儿缩略图，不写文件。
     * 删除规则后调用，保证本体镜像不残留已删规则的图。
     */
    fun pushThumbKeySet(context: Context, packageName: String, keys: Collection<String>) {
        runCatching {
            val safePkg = sanitizeMirrorPackageName(packageName) ?: return
            val map = LinkedHashMap<String, String>()
            for (key in keys) {
                if (key.matches(THUMB_KEY_REGEX)) {
                    map[key] = ""
                }
            }
            sendThumbBatch(
                context.applicationContext,
                safePkg,
                map,
                RuleMirrorProtocol.THUMB_MODE_REPLACE,
            )
        }.onFailure {
            GvLog.w(TAG, "push thumb key set failed", it)
        }
    }

    /** 发送一个缩略图批次（[mode] 为 merge / replace）。 */
    fun sendThumbBatch(
        context: Context,
        packageName: String,
        thumbs: Map<String, String>,
        mode: String,
    ) {
        runCatching {
            val safePkg = sanitizeMirrorPackageName(packageName) ?: return
            if (thumbs.isEmpty()) return
            val json = gson.toJson(
                MirrorFile(
                    schemaVersion = 1,
                    packageName = safePkg,
                    updatedAt = System.currentTimeMillis(),
                    thumbnails = thumbs,
                    thumbnailsMode = mode,
                    rules = null,
                ),
            )
            if (json.toByteArray(Charsets.UTF_8).size > RuleMirrorProtocol.MAX_JSON_BYTES) {
                GvLog.w(TAG, "thumb batch too large (${json.length}), skip")
                return
            }
            val intent = Intent(RuleMirrorProtocol.ACTION_MIRROR_THUMBS).apply {
                component = ComponentName(
                    BuildConfig.PACKAGE_NAME,
                    RuleMirrorProtocol.RECEIVER_CLASS,
                )
                putExtra(RuleMirrorProtocol.EXTRA_PACKAGE, safePkg)
                putExtra(RuleMirrorProtocol.EXTRA_JSON, json)
                putExtra(RuleMirrorProtocol.EXTRA_TOKEN, RuleMirrorProtocol.MIRROR_TOKEN)
            }
            context.applicationContext.sendBroadcast(intent)
            GvLog.d(TAG, "thumb batch sent: $safePkg mode=$mode keys=${thumbs.size}")
        }.onFailure {
            GvLog.w(TAG, "send thumb batch failed", it)
        }
    }

    private fun encodeAppIcon(context: Context, packageName: String): String? {
        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = RuleMirrorCodec.drawableToBitmap(drawable, 96)
            RuleMirrorCodec.bitmapToBase64Png(bitmap, quality = 90)
        }.getOrNull()
    }

    private val THUMB_KEY_REGEX = Regex("^[a-f0-9]{8,64}$")
}
