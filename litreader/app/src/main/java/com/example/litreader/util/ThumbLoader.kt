package com.example.litreader.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.example.litreader.R
import com.example.litreader.data.remote.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * 列表缩略图加载：进程级内存 LRU + 后台降采样解码。
 * 复用条目通过 view.tag 比对 URL 防错位回填；视图弱引用不泄漏；
 * 无磁盘缓存（列表图小，网络二次拉取可接受）。
 */
object ThumbLoader {

    private const val TARGET = 120  // 56dp@xxhdpi 足够

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 12).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(url: String, view: ImageView) {
        view.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            (view.getTag(R.id.thumbJob) as? Job)?.cancel()
            view.alpha = 1f
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        (view.getTag(R.id.thumbJob) as? Job)?.cancel()
        val ref = WeakReference(view)
        val job: Job = scope.launch {
            val bmp = runCatching {
                val bytes = HttpClient.fetch(url)
                decode(bytes)
            }.getOrNull()
            if (bmp != null) cache.put(url, bmp)
            withContext(Dispatchers.Main) {
                val iv = ref.get() ?: return@withContext
                if (iv.tag != url) return@withContext  // 已被复用绑定到别的条目
                iv.setImageBitmap(bmp)
                iv.animate().alpha(1f).setDuration(200).start()
                iv.setTag(R.id.thumbJob, null)
            }
        }
        view.setTag(R.id.thumbJob, job)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
