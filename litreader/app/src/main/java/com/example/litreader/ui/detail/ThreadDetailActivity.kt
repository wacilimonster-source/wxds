package com.example.litreader.ui.detail

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.data.model.Post
import com.example.litreader.databinding.ActivityDetailBinding
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

class ThreadDetailActivity : AppCompatActivity() {
    private lateinit var bind: ActivityDetailBinding
    private lateinit var repo: BookRepository
    private var tid: String = ""
    private var onlyOp = true
    private var posts: List<Post> = emptyList()
    private var floorPage = 1
    private var fontSize = 17
    private var favorite = false
    private var fromCache = false

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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        bind.web.settings.javaScriptEnabled = false
        bind.web.settings.loadWithOverviewMode = true
        WebView.setWebContentsDebuggingEnabled(false)
        // 跟随应用日/夜主题，正文底色与界面一致
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (dark) bind.web.setBackgroundColor(ContextCompat.getColor(this, R.color.bgPaper))
    }

    private fun totalPages(): Int = max(1, ceil(posts.size / FLOORS_PER_PAGE.toDouble()).toInt())

    private fun load() {
        bind.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val (p, cached) = repo.getThread(tid, onlyOp)
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

    private fun renderFloorBar() {
        val total = totalPages()
        if (posts.isEmpty()) {
            bind.tvFloorPage.text = if (onlyOp) "只看楼主 · 未加载" else "全部楼层 · 未加载"
            bind.tvFloorPos.text = "—"
        } else {
            bind.tvFloorPage.text = if (onlyOp) "只看楼主 · 共 ${posts.size} 楼" else "全部楼层 · 共 ${posts.size} 楼"
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
