package com.example.litreader.ui.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.util.LruCache
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.litreader.R
import com.example.litreader.data.remote.HttpClient
import com.example.litreader.databinding.ActivityGalleryBinding
import com.example.litreader.databinding.ItemGalleryPageBinding
import com.example.litreader.util.MediaSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : AppCompatActivity() {
    private lateinit var bind: ActivityGalleryBinding
    private lateinit var urls: List<String>
    private var barsVisible = true

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) saveCurrent() else toast(getString(R.string.save_need_permission))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(bind.root)

        window.statusBarColor = ContextCompat.getColor(this, R.color.galleryBg)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0 // 深色顶栏，状态栏图标保持浅色

        urls = intent.getStringArrayListExtra("urls")?.toList() ?: emptyList()
        if (urls.isEmpty()) {
            toast(getString(R.string.gallery_empty))
            finish()
            return
        }
        bind.tvTitle.text = intent.getStringExtra("title") ?: ""

        bind.pager.adapter = GalleryPagerAdapter(urls, lifecycleScope) { toggleBars() }
        bind.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateCounter()
        })
        val start = intent.getIntExtra("index", 0).coerceIn(0, urls.size - 1)
        bind.pager.setCurrentItem(start, false)
        updateCounter()

        bind.btnBack.setOnClickListener { finish() }
        bind.btnSave.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 29 || hasWritePermission()) saveCurrent()
            else permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        bind.btnShare.setOnClickListener { shareCurrent() }
    }

    private fun hasWritePermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    private fun currentUrl(): String = urls[bind.pager.currentItem]

    private fun saveCurrent() {
        val url = currentUrl()
        lifecycleScope.launch {
            try {
                bind.progress.visibility = View.VISIBLE
                val bytes = withContext(Dispatchers.IO) { HttpClient.fetch(url) }
                val ok = withContext(Dispatchers.IO) {
                    MediaSaver.saveToGallery(applicationContext, bytes, url)
                }
                toast(getString(if (ok) R.string.save_done else R.string.save_failed))
            } catch (e: Exception) {
                toast(getString(R.string.save_failed))
            } finally {
                bind.progress.visibility = View.GONE
            }
        }
    }

    private fun shareCurrent() {
        val url = currentUrl()
        lifecycleScope.launch {
            try {
                bind.progress.visibility = View.VISIBLE
                val bytes = withContext(Dispatchers.IO) { HttpClient.fetch(url) }
                val uri = withContext(Dispatchers.IO) {
                    MediaSaver.cacheForShare(applicationContext, bytes, url)
                }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = MediaSaver.mimeType(url)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, getString(R.string.share_image)))
            } catch (e: Exception) {
                toast(getString(R.string.share_failed))
            } finally {
                bind.progress.visibility = View.GONE
            }
        }
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        bind.topBar.visibility = if (barsVisible) View.VISIBLE else View.GONE
        bind.bottomBar.visibility = if (barsVisible) View.VISIBLE else View.GONE
    }

    private fun updateCounter() {
        bind.tvCounter.text = getString(R.string.gallery_counter, bind.pager.currentItem + 1, urls.size)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/** 图片内存缓存 + 后台解码。 */
object GalleryLoader {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(url: String, scope: CoroutineScope, onReady: (Bitmap?) -> Unit) {
        val cached = cache.get(url)
        if (cached != null) {
            onReady(cached)
            return
        }
        scope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                val bytes = HttpClient.fetch(url)
                decode(bytes)
            }.getOrNull()
            if (bmp != null) cache.put(url, bmp)
            withContext(Dispatchers.Main) { onReady(bmp) }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: OutOfMemoryError) {
            val downscaled = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, downscaled)
        }
    }
}

class GalleryPagerAdapter(
    private val urls: List<String>,
    private val scope: CoroutineScope,
    private val onSingleTap: () -> Unit
) : RecyclerView.Adapter<GalleryPagerAdapter.VH>() {

    class VH(val b: ItemGalleryPageBinding) : RecyclerView.ViewHolder(b.root) {
        var boundUrl: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemGalleryPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.image.tapListener = ZoomableImageView.TapListener { onSingleTap() }
        return VH(b)
    }

    override fun getItemCount() = urls.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val url = urls[position]
        h.boundUrl = url
        h.b.image.setImageDrawable(null)
        h.b.image.reset()
        h.b.progress.visibility = View.VISIBLE
        GalleryLoader.load(url, scope) { bmp ->
            if (h.boundUrl != url) return@load
            if (bmp == null) {
                h.b.progress.visibility = View.GONE
                Toast.makeText(h.b.root.context, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
            } else {
                h.b.image.setBitmap(bmp)
                h.b.progress.visibility = View.GONE
            }
        }
    }
}
