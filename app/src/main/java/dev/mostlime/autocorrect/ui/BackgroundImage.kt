package dev.mostlime.autocorrect.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File

/**
 * The keyboard background: the picked image copied into app storage at (at most) screen width, as one JPEG. Read right
 * away in the picker callback (the URI grant belongs to the settings activity). ImageDecoder on Android 9+ (HEIC,
 * EXIF rotation, decoded straight at the target size), BitmapFactory with subsampling before that.
 */
object BackgroundImage {
    const val TAG = "AcBg"

    /** -> null when saved, else what went wrong */
    fun save(ctx: Context, uri: Uri, out: File, maxWidth: Int): String? = try {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                if (w > maxWidth) dec.setTargetSize(maxWidth, (h.toLong() * maxWidth / w).toInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else decodeLegacy(ctx, uri, maxWidth)
        if (bmp == null) "this image format can't be read" else {
            val tmp = File(out.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            if (!tmp.renameTo(out)) { tmp.delete(); "couldn't save the image" } else null
        }
    } catch (e: Throwable) {
        Log.w(TAG, "background image failed", e)
        "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun decodeLegacy(ctx: Context, uri: Uri, maxWidth: Int): Bitmap? {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        if (src.width <= maxWidth) return src
        val h = (src.height.toLong() * maxWidth / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxWidth, h, true).also { if (it !== src) src.recycle() }
    }
}
