package com.swami.ustwo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class Prepared(val jpeg: ByteArray, val preview: Bitmap)

object Images {
    private const val MAX_UPLOAD = 1600

    /** Decode any picked image (JPEG/HEIC/PNG, EXIF rotation applied), shrink to 1600px, re-encode JPEG. */
    fun prepareUpload(ctx: Context, uri: Uri): Prepared {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            var sample = 1
            while (longest / (sample * 2) >= MAX_UPLOAD) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
        val scaled = scaleDown(decoded, MAX_UPLOAD)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Prepared(out.toByteArray(), scaled)
    }

    fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxSide) return b
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            b,
            (b.width * s).roundToInt().coerceAtLeast(1),
            (b.height * s).roundToInt().coerceAtLeast(1),
            true
        )
    }

    fun decodeFile(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        return scaleDown(bmp, maxSide)
    }
}

/** On-disk + in-memory photo cache so the widget and history work offline. */
object Cache {
    private val memory = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun latestFile(ctx: Context) = File(ctx.filesDir, "latest.jpg")

    private fun photoFile(ctx: Context, path: String) =
        File(File(ctx.cacheDir, "photos").apply { mkdirs() }, path.replace('/', '_'))

    fun put(ctx: Context, path: String, bytes: ByteArray) = atomicWrite(photoFile(ctx, path), bytes)

    fun read(ctx: Context, path: String): ByteArray? =
        photoFile(ctx, path).takeIf { it.exists() }?.readBytes()

    fun setLatest(ctx: Context, bytes: ByteArray) {
        atomicWrite(latestFile(ctx), bytes)
        memory.evictAll()
    }

    /** Blocking: returns a bitmap for a post, downloading it from GitHub if needed. */
    fun bitmap(ctx: Context, path: String, maxSide: Int): Bitmap? {
        val key = "$path@$maxSide"
        memory.get(key)?.let { return it }
        val file = photoFile(ctx, path)
        if (!file.exists()) {
            val cfg = Store(ctx).config ?: return null
            put(ctx, path, GitHub(cfg).download(path))
        }
        return Images.decodeFile(file, maxSide)?.also { memory.put(key, it) }
    }

    fun clearAll(ctx: Context) {
        memory.evictAll()
        File(ctx.cacheDir, "photos").deleteRecursively()
        latestFile(ctx).delete()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }
}
