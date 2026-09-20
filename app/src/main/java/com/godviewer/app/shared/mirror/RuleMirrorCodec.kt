package com.godviewer.app.shared.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import com.godviewer.app.shared.ViewSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 镜像推送/落盘用的图片编解码（PNG base64 ↔ 文件 / Drawable）。
 * 无进程角色语义，host push 裁剪与 host 写盘共用。
 */
internal object RuleMirrorCodec {

    fun drawableToBitmap(drawable: Drawable, max: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return ViewSnapshot.scaleDown(drawable.bitmap, max)
        }
        val w = (drawable.intrinsicWidth.takeIf { it > 0 } ?: max).coerceAtMost(max)
        val h = (drawable.intrinsicHeight.takeIf { it > 0 } ?: max).coerceAtMost(max)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun bitmapToBase64Png(bitmap: Bitmap, quality: Int): String {
        val scaled = ViewSnapshot.scaleDown(bitmap, 128)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 缩略图专用编码：JPEG（白底合成，避免透明区变黑）。
     *
     * 同样 128px 下比 PNG 小 3–5 倍，广播批次能多装几倍，镜像缩略图更不容易被截断。
     * 宿主落盘按内容解码（BitmapFactory 不看扩展名），与历史 PNG 文件可以共存。
     */
    fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int): String {
        val scaled = ViewSnapshot.scaleDown(bitmap, 128)
        val opaque = flattenOnWhite(scaled)
        val baos = ByteArrayOutputStream()
        opaque.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /** 有 alpha 的位图合成到白底（JPEG 无 alpha 通道）。 */
    private fun flattenOnWhite(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        return runCatching {
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(source, 0f, 0f, null)
            out
        }.getOrDefault(source)
    }

    fun decodeBase64ToFile(b64: String?, file: File) {
        if (b64.isNullOrBlank()) return
        runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (bytes.isEmpty() || bytes.size > 512 * 1024) return
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}
