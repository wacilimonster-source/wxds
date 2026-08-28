package com.example.litreader.ui.detail

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.model.Post
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.SourceStyle
import com.example.litreader.databinding.ActivityDetailBinding
import com.example.litreader.ui.gallery.GalleryActivity
import com.example.litreader.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.jsoup.Jsoup
import kotlin.math.ceil
import kotlin.math.max

class ThreadDetailActivity : AppCompatActivity() {
    private lateinit var bind: ActivityDetailBinding
    private lateinit var repo: BookRepository
    private var sourceId: String = ""
    private var tid: String = ""
    private var onlyOp = true
    private var posts: List<Post> = emptyList()
    private var floorPage = 1
    private var fontSize = 17
    private var favorite = false
    private var fromCache = false
    /** 当前帖子正文里出现的图片地址（按楼层顺序），供画廊使用（JS 线程会读，需 volatile） */
    @kotlin.jvm.Volatile
    private var imageUrls: List<String> = emptyList()

    companion object {
        private const val FLOORS_PER_PAGE = 20
        private const val BASE_URL = "https://www.t66y.com"
        private const val PREF_FONT = "reader_font"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(bind.root)
        repo = BookRepository((application as App).database)

        tid = intent.getStringExtra("tid") ?: return finish()
        val chapterTitle = intent.getStringExtra("title") ?: ""
        bind.tvChapter.text = chapterTitle
        favorite = intent.getBooleanExtra("favorite", false)
        fontSize = getSharedPreferences("reader", MODE_PRIVATE).getInt(PREF_FONT, 17)
        renderFav()
        renderFloorBar()

        lifecycleScope.launch {
            sourceId = withContext(Dispatchers.IO) {
                intent.getStringExtra("sourceId")
                    ?: repo.threadSourceId(tid)
                    ?: SourceRegistry.first().id
            }
            val source = SourceRegistry.get(sourceId) ?: SourceRegistry.first()
            if (source.style == SourceStyle.IMAGE) setupImageMode()
            else bind.btnReader.visibility = View.VISIBLE
            bind.btnReader.setOnClickListener {
                startActivity(Intent(this@ThreadDetailActivity, ReaderActivity::class.java).apply {
                    putExtra("tid", tid)
                    putExtra("title", bind.tvChapter.text.toString())
                    putExtra("sourceId", sourceId)
                })
                finish()
            }
            setupWeb()
            bind.btnBack.setOnClickListener { finish() }
            bind.btnOp.setOnClickListener {
                onlyOp = !onlyOp
                load()
            }
            bind.btnFav.setOnClickListener {
                lifecycleScope.launch {
                    favorite = !favorite
                    repo.setFavorite(tid, favorite)
                    renderFav()
                }
            }
            bind.btnFontUp.setOnClickListener { setFont(fontSize + 1) }
            bind.btnFontDown.setOnClickListener { setFont(fontSize - 1) }
            bind.btnPrevFloor.setOnClickListener { if (floorPage > 1) { floorPage--; renderPosts() } }
            bind.btnNextFloor.setOnClickListener {
                if (floorPage < totalPages()) { floorPage++; renderPosts() }
            }
            load()
        }
    }

    /** 图片帖：字号控件无意义，隐藏；开启 JS 注入点击看大图。 */
    private fun setupImageMode() {
        bind.btnFontDown.visibility = View.GONE
        bind.btnFontUp.visibility = View.GONE
        bind.tvFont.visibility = View.GONE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val source = SourceRegistry.get(sourceId)
        val isImage = source?.style == SourceStyle.IMAGE
        bind.web.settings.javaScriptEnabled = isImage
        bind.web.settings.loadWithOverviewMode = true
        bind.web.settings.mediaPlaybackRequiresUserGesture = true
        if (isImage) {
            bind.web.addJavascriptInterface(Bridge(), "AppBridge")
            bind.web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(BIND_IMAGE_CLICKS_JS, null)
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(false)
        // 跟随应用日/夜主题，正文底色与界面一致
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (dark) bind.web.setBackgroundColor(ContextCompat.getColor(this, R.color.bgPaper))
    }

    private inner class Bridge {
        @JavascriptInterface
        fun tapImage(src: String) {
            val urls = imageUrls
            var idx = urls.indexOf(src)
            val list = if (idx >= 0) urls else {
                idx = urls.size
                urls + src
            }
            if (list.isEmpty()) return
            runOnUiThread {
                startActivity(Intent(this@ThreadDetailActivity, GalleryActivity::class.java).apply {
                    putStringArrayListExtra("urls", ArrayList(list))
                    putExtra("index", idx)
                    putExtra("title", bind.tvChapter.text.toString())
                })
            }
        }
    }

    private fun totalPages(): Int = max(1, ceil(posts.size / FLOORS_PER_PAGE.toDouble()).toInt())

    private fun load() {
        bind.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val (p, cached) = repo.getThread(sourceId, tid, onlyOp)
                posts = p
                fromCache = cached
                floorPage = 1
                renderPosts()
            } catch (e: Exception) {
                bind.web.loadDataWithBaseURL(
                    BASE_URL, readerHtml("<p>加载失败：${e.message ?: "网络错误"}</p><p>已缓存的章节在联网后仍可离线重读。</p>"),
                    "text/html", "utf-8", null
                )
            } finally {
                bind.progress.visibility = View.GONE
            }
        }
    }

    private fun readerHtml(body: String): String {
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) "#15120E" else "#FAF6EF"
        val fg = if (dark) "#EDE5D8" else "#241E17"
        val subtle = if (dark) "#A3988A" else "#8A7F70"
        val line = if (dark) "#2E2820" else "#EAE2D6"
        val badge = if (fromCache)
            "<div class='badge'>离线缓存</div>" else ""
        return StringBuilder().apply {
            append("<html><head><meta charset='utf-8'/><meta name='viewport' content='width=device-width, initial-scale=1'/>")
            append("<style>")
            append("body{background:$bg;color:$fg;font-size:${fontSize}px;line-height:1.9;padding:18px 16px 28px;word-break:break-word;font-family:serif}")
            append("img{max-width:100%;height:auto;border-radius:8px}")
            append("video{max-width:100%;border-radius:8px}")
            append(".post{padding:14px 0;border-bottom:1px solid $line}")
            append(".meta{color:$subtle;font-size:${fontSize - 5}px;margin-bottom:8px}")
            append(".badge{display:inline-block;background:$line;color:$subtle;font-size:${fontSize - 6}px;padding:3px 10px;border-radius:99px;margin-bottom:4px}")
            append("a{color:inherit}")
            append("</style></head><body>")
            append(badge)
            append(body)
            append("</body></html>")
        }.toString()
    }

    private fun renderPosts() {
        collectImageUrls()
        if (posts.isEmpty()) {
            bind.web.loadDataWithBaseURL(BASE_URL, readerHtml("<p>暂无内容，可能需要登录或帖子已被删除。</p>"), "text/html", "utf-8", null)
            renderFloorBar()
            return
        }
        val from = (floorPage - 1) * FLOORS_PER_PAGE
        val to = minOf(posts.size, from + FLOORS_PER_PAGE)
        val body = StringBuilder()
        posts.subList(from, to).forEach { p ->
            body.append("<div class='post'>")
            body.append("<div class='meta'>")
            if (p.author.isNotEmpty()) body.append("${p.author} · ")
            if (p.dateText.isNotEmpty()) body.append("${p.dateText} · ")
            body.append("#${p.floor}樓")
            body.append("</div>")
            body.append(p.contentHtml)
            body.append("</div>")
        }
        bind.web.loadDataWithBaseURL(BASE_URL, readerHtml(body.toString()), "text/html", "utf-8", null)
        renderFloorBar()
    }

    /** 从全部楼层正文提取图片地址（懒加载已在抓取阶段补到 src），供画廊浏览。 */
    private fun collectImageUrls() {
        val urls = LinkedHashSet<String>()
        for (p in posts) {
            runCatching {
                Jsoup.parseBodyFragment(p.contentHtml).select("img[src]").forEach { img ->
                    val src = img.attr("src").trim()
                    if (src.startsWith("http") && !src.contains("adblo_ck")) urls.add(src)
                }
            }
        }
        imageUrls = urls.toList()
    }

    private fun renderFloorBar() {
        val total = totalPages()
        if (posts.isEmpty()) {
            bind.tvFloorPage.text = if (onlyOp) "只看楼主 · 未加载" else "全部楼层 · 未加载"
            bind.tvFloorPos.text = "—"
        } else {
            val extra = if (imageUrls.isNotEmpty()) " · ${imageUrls.size} 图" else ""
            bind.tvFloorPage.text = if (onlyOp) "只看楼主 · 共 ${posts.size} 楼$extra" else "全部楼层 · 共 ${posts.size} 楼$extra"
            bind.tvFloorPos.text = "第 $floorPage / $total 页"
        }
        bind.tvFloorPos.visibility = if (posts.isEmpty()) View.GONE else View.VISIBLE
        bind.btnPrevFloor.isEnabled = floorPage > 1
        bind.btnNextFloor.isEnabled = floorPage < total
        bind.btnOp.text = if (onlyOp) getString(R.string.see_all) else getString(R.string.only_op)
        val alpha = if (bind.btnPrevFloor.isEnabled) 1f else 0.35f
        bind.btnPrevFloor.alpha = alpha
        bind.btnNextFloor.alpha = if (bind.btnNextFloor.isEnabled) 1f else 0.35f
    }

    private fun renderFav() {
        bind.btnFav.setImageResource(
            if (favorite) R.drawable.ic_star_fill else R.drawable.ic_star_outline
        )
    }

    private fun setFont(size: Int) {
        fontSize = size.coerceIn(12, 30)
        bind.tvFont.text = "$fontSize"
        getSharedPreferences("reader", MODE_PRIVATE).edit().putInt(PREF_FONT, fontSize).apply()
        if (posts.isNotEmpty()) renderPosts()
    }
}

/** 给正文里的图片挂点击事件，把地址回传给 AppBridge。 */
private const val BIND_IMAGE_CLICKS_JS =
    "(function(){for(var i=0;i<document.images.length;i++){(function(im){" +
        "im.style.cursor='pointer';" +
        "im.addEventListener('click',function(){AppBridge.tapImage(im.currentSrc||im.src)})" +
        "})(document.images[i])}})()"
