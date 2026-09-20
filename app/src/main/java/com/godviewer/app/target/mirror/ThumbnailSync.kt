package com.godviewer.app.target.mirror

import android.content.Context
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.mirror.RuleMirrorCodec
import com.godviewer.app.shared.mirror.RuleMirrorProtocol
import com.godviewer.app.shared.mirror.mirrorThumbnailKey
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.rule.ViewRuleThumbnails

/**
 * 目标进程缩略图回补：
 *
 * - 回放时补抓到的新缩略图（[flushNewThumbnails]）会增量推给宿主，
 *   让历史镜像里缺失的图随日常使用自愈；限频避免每次 onResume 都广播。
 * - 删除规则后（[syncKeySetAfterDelete]）回推一次全量 key 列表（replace），
 *   宿主据此清掉孤儿缩略图。
 *
 * 全程 best-effort：任何异常都不允许影响目标应用。
 */
internal object ThumbnailSync {
    private const val TAG = "Mirror"
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L
    private const val THUMB_JPEG_QUALITY = 85

    @Volatile
    private var lastPushAt = 0L

    /**
     * 回放补抓到新缩略图后调用（主线程）。限频未到时**不清空**待推集合，
     * 留到下次一起推。
     */
    fun flushNewThumbnails(context: Context) {
        runCatching {
            if (ViewRuleThumbnails.pendingCount() == 0) return
            val now = System.currentTimeMillis()
            if (now - lastPushAt < MIN_INTERVAL_MS) return
            lastPushAt = now
            val newThumbs = ViewRuleThumbnails.drainPendingNew()
            if (newThumbs.isEmpty()) return
            val pkg = context.packageName
            var batch = LinkedHashMap<String, String>()
            var bytes = 0
            for ((key, bmp) in newThumbs) {
                val b64 = runCatching {
                    RuleMirrorCodec.bitmapToBase64Jpeg(bmp, THUMB_JPEG_QUALITY)
                }.getOrNull() ?: continue
                if (b64.length > RuleMirrorProtocol.MAX_SINGLE_THUMB_BYTES) continue
                if (bytes + b64.length > RuleMirrorProtocol.MAX_THUMB_BATCH_BYTES &&
                    batch.isNotEmpty()
                ) {
                    RuleMirrorPush.sendThumbBatch(
                        context,
                        pkg,
                        batch,
                        RuleMirrorProtocol.THUMB_MODE_MERGE,
                    )
                    batch = LinkedHashMap()
                    bytes = 0
                }
                batch[key] = b64
                bytes += b64.length
            }
            if (batch.isNotEmpty()) {
                RuleMirrorPush.sendThumbBatch(
                    context,
                    pkg,
                    batch,
                    RuleMirrorProtocol.THUMB_MODE_MERGE,
                )
            }
            GvLog.d(TAG, "new thumbnails flushed: ${newThumbs.size}")
        }.onFailure {
            GvLog.w(TAG, "flush new thumbnails failed", it)
        }
    }

    /** 删除规则后同步一次全量 key 列表，宿主清掉孤儿图。 */
    fun syncKeySetAfterDelete(context: Context, rules: List<ViewRule>) {
        runCatching {
            val keys = rules.map { mirrorThumbnailKey(it) }
            RuleMirrorPush.pushThumbKeySet(context, context.packageName, keys)
        }.onFailure {
            GvLog.w(TAG, "sync thumb key set failed", it)
        }
    }
}
