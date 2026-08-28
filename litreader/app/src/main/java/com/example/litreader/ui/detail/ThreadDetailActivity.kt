package com.example.litreader.ui.detail

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.litreader.App
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        bind = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(bind.root)
        repo = BookRepository((application as App).database)

        tid = intent.getStringExtra("tid") ?: return finish()
        title = intent.getStringExtra("title")
        favorite = intent.getBooleanExtra("favorite", false)
        fontSize = getSharedPreferences("reader", MODE_PRIVATE).getInt(PREF_FONT, 17)
        renderFav()

        setupWeb()
        renderFloorBar()

        bind.btnOp.setOnClickListener {
            onlyOp = !onlyOp
            bind.btnOp.text = if (onlyOp) "看全部" else "只看楼主"
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
        bind.web.settings.builtInZoomControls = false
        bind.web.settings.loadWithOverviewMode = true
        WebView.setWebContentsDebuggingEnabled(false)
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
                    BASE_URL,
                    "<html><body style='font-size:16px;padding:24px'>加载失败：${e.message ?: "网络错误"}<br/><br/>已缓存的正文会在此展示（当前分类无缓存）。</body></html>",
                    "text/html", "utf-8", null
                )
            } finally {
                bind.progress.visibility = View.GONE
            }
        }
    }

    private fun renderPosts() {
        if (posts.isEmpty()) {
            bind.web.loadDataWithBaseURL(BASE_URL, "<html><body style='padding:24px;font-size:16px'>暂无内容（可能需要登录或帖子已被删除）</body></html>", "text/html", "utf-8", null)
            renderFloorBar()
            return
        }
        val from = (floorPage - 1) * FLOORS_PER_PAGE
        val to = minOf(posts.size, from + FLOORS_PER_PAGE)
        val slice = posts.subList(from, to)
        val cacheBadge = if (fromCache) "<div style='text-align:center;color:#999;font-size:12px'>📄 离线缓存</div>" else ""
        val html = StringBuilder().apply {
            append("<html><head><meta charset='utf-8'/><meta name='viewport' content='width=device-width, initial-scale=1'/>")
            append("<style>body{font-size:${fontSize}px;line-height:1.8;padding:12px;word-break:break-word}img{max-width:100%;height:auto}")
            append(".post{border-bottom:1px solid #ddd;padding-bottom:12px;margin-bottom:12px}")
            append(".meta{color:#888;font-size:${fontSize - 4}px;margin-bottom:6px}")
            append("hr{border:none;border-top:1px solid #eee}</style></head><body>")
            append(cacheBadge)
            slice.forEach { p ->
                append("<div class='post'>")
                val meta = buildString {
                    append("<span class='meta'>")
                    if (p.author.isNotEmpty()) append("${p.author} ")
                    if (p.dateText.isNotEmpty()) append("· ${p.dateText} ")
                    append("· #${p.floor}樓")
                    append("</span>")
                }
                append(meta)
                append(p.contentHtml)
                append("</div>")
            }
            append("</body></html>")
        }.toString()
        bind.web.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
        renderFloorBar()
    }

    private fun renderFloorBar() {
        val total = totalPages()
        bind.tvFloorPage.text = if (posts.isEmpty()) "—" else "第 $floorPage/$total 页 · 共 ${posts.size} 楼"
        bind.btnPrevFloor.isEnabled = floorPage > 1
        bind.btnNextFloor.isEnabled = floorPage < total
        if (onlyOp) {
            bind.btnOp.text = "看全部"
        } else {
            bind.btnOp.text = "只看楼主"
        }
    }

    private fun renderFav() {
        bind.btnFav.setImageResource(
            if (favorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )
    }

    private fun setFont(size: Int) {
        fontSize = size.coerceIn(12, 30)
        getSharedPreferences("reader", MODE_PRIVATE).edit().putInt(PREF_FONT, fontSize).apply()
        renderPosts()
    }
}
