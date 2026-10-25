package com.example.litreader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.litreader.data.remote.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 阅读器内联图加载：内存 LruCache + 磁盘缓存（cacheDir/inline_imgs），
 * 回调在主线程；失败回调 null（正文保留占位块）。
 */
object InlineImages {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var diskDir: File? = null

    fun load(context: Context, url: String, targetWidth: Int, scope: CoroutineScope, onReady: (Bitmap?) -> Unit) {
        mem.get(url)?.let { onReady(it); return }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val f = diskFile(context, url)
                    val bytes = if (f.isFile && f.length() > 0) f.readBytes()
                    else HttpClient.fetch(url).also { saveQuietly(f, it) }
                    decode(bytes, targetWidth)
                }.onFailure { android.util.Log.d("InlineImg", "load fail: $url", it) }
                    .getOrNull()
            }
            if (bmp != null) mem.put(url, bmp)
            withContext(Dispatchers.Main) { onReady(bmp) }
        }
    }

    private fun diskFile(context: Context, url: String): File {
        val dir = diskDir ?: File(context.cacheDir, "inline_imgs").apply { mkdirs() }.also { diskDir = it }
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".img")
    }

    private fun saveQuietly(f: File, bytes: ByteArray) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) f.writeBytes(bytes)
        }
    }

    /** 先读尺寸再按目标宽降采样，避免论坛原图直接解码爆内存。 */
    private fun decode(bytes: ByteArray, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outWidth / sample > targetWidth * 3 / 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
