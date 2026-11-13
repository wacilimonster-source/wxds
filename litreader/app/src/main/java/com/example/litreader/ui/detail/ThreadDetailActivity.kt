package com.example.litreader.ui.detail

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.model.Post
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.databinding.ActivityDetailBinding
import com.example.litreader.ui.reader.ReaderActivity
import com.example.litreader.util.NetError
import com.example.litreader.util.ReadProgress
import com.example.litreader.util.UiTips
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
    /** 站点详情页（游客每页约 3 帖）；「全部楼层」按站点页继续加载。 */
    private var sitePage = 1
    private var siteTotalPages = 1
    private var loadingMore = false
    private var hasMoreSitePages = false
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

    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.lr_stay, R.anim.lr_exit)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.lr_stay, R.anim.lr_exit)
        }
    }

    override fun onPause() {
        // 收藏/已读可能在这里变过：通知列表只回读这一条
        ReadProgress.markDirty(this, sourceId, tid)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(bind.root)
        repo = BookRepository((application as App).database)

        // 与阅读器同款进出转场
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, R.anim.lr_enter, R.anim.lr_stay)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.lr_enter, R.anim.lr_stay)
        }
        tid = intent.getStringExtra("tid") ?: return finish()
        val chapterTitle = intent.getStringExtra("title") ?: ""
        bind.tvChapter.text = chapterTitle
        favorite = intent.getBooleanExtra("favorite", false)
        fontSize = getSharedPreferences("reader", MODE_PRIVATE).getInt(PREF_FONT, 17)
        renderFav()
        renderFloorBar()

        lifecycleScope.launch {
            runCatching { repo.markOpened(tid) }
            sourceId = withContext(Dispatchers.IO) {
                intent.getStringExtra("sourceId")
                    ?: repo.threadSourceId(tid)
                    ?: SourceRegistry.first().id
            }
            val source = SourceRegistry.get(sourceId) ?: SourceRegistry.first()
            bind.btnReader.visibility = View.VISIBLE
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
                if (!onlyOp) {
                    if (hasMoreSitePages && !loadingMore) loadMoreFloors()
                } else if (floorPage < totalPages()) {
                    floorPage++
                    renderPosts()
                }
            }
            load()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        // 仅服务文学帖的「论坛楼层视图」；图片帖已直进原生阅读器
        bind.web.settings.loadWithOverviewMode = true
        WebView.setWebContentsDebuggingEnabled(false)
        // 跟随应用日/夜主题，正文底色与界面一致
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (dark) bind.web.setBackgroundColor(ContextCompat.getColor(this, R.color.bgPaper))
    }

    private fun totalPages(): Int =
        if (!onlyOp) 1 else max(1, ceil(posts.size / FLOORS_PER_PAGE.toDouble()).toInt())

    private fun load() {
        bind.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val (pageData, cached) = repo.getThreadPage(sourceId, tid, onlyOp, 1)
                posts = pageData.posts
                fromCache = cached
                floorPage = 1
                sitePage = 1
                siteTotalPages = if (onlyOp) 1 else pageData.totalPages.coerceAtLeast(1)
                // 旧版缓存没有 total；先给一次「加载更多」入口，点一次即可校正。
                hasMoreSitePages = !onlyOp && (siteTotalPages > 1 || cached)
                renderPosts()
                // 打开即记为已读（不影响已读完标记）
                repo.markRead(tid)
            } catch (e: Exception) {
                bind.web.loadDataWithBaseURL(
                    BASE_URL, readerHtml("<p>加载失败：${NetError.friendly(e)}</p><p>已缓存的章节在联网后仍可离线重读。</p>"),
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

    private fun renderPosts(scrollToBottom: Boolean = false) {
        collectImageUrls()
        if (posts.isEmpty()) {
            bind.web.loadDataWithBaseURL(BASE_URL, readerHtml("<p>暂无内容，可能需要登录或帖子已被删除。</p>"), "text/html", "utf-8", null)
            renderFloorBar()
            return
        }
        // 「全部楼层」把已加载的站点页一次渲染完；「只看楼主」仍按 20 楼本地分页。
        val from = if (!onlyOp) 0 else (floorPage - 1) * FLOORS_PER_PAGE
        val to = if (!onlyOp) posts.size else minOf(posts.size, from + FLOORS_PER_PAGE)
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
        if (scrollToBottom) {
            bind.web.postDelayed({ bind.web.scrollTo(0, bind.web.contentHeight) }, 350)
        }
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
        } else if (onlyOp) {
            val extra = if (imageUrls.isNotEmpty()) " · ${imageUrls.size} 图" else ""
            bind.tvFloorPage.text = "只看楼主 · 共 ${posts.size} 楼$extra"
            bind.tvFloorPos.text = "第 $floorPage / $total 页"
        } else {
            val extra = if (imageUrls.isNotEmpty()) " · ${imageUrls.size} 图" else ""
            val more = when {
                loadingMore -> " · 加载中…"
                hasMoreSitePages -> " · ${getString(R.string.floor_load_more)}"
                else -> " · ${getString(R.string.floor_all_loaded)}"
            }
            bind.tvFloorPage.text = "${getString(R.string.floor_loaded_n, posts.size)}$extra$more"
            bind.tvFloorPos.text = "第 $sitePage / $siteTotalPages 页"
        }
        bind.tvFloorPos.visibility = if (posts.isEmpty()) View.GONE else View.VISIBLE
        bind.btnPrevFloor.isEnabled = onlyOp && floorPage > 1
        bind.btnNextFloor.isEnabled = if (onlyOp) floorPage < total else hasMoreSitePages && !loadingMore
        bind.tvFloorPage.isClickable = !onlyOp && hasMoreSitePages && !loadingMore
        bind.tvFloorPage.setOnClickListener {
            if (!onlyOp && hasMoreSitePages && !loadingMore) loadMoreFloors()
        }
        bind.btnOp.text = if (onlyOp) getString(R.string.see_all) else getString(R.string.only_op)
        bind.btnPrevFloor.alpha = if (bind.btnPrevFloor.isEnabled) 1f else 0.35f
        bind.btnNextFloor.alpha = if (bind.btnNextFloor.isEnabled) 1f else 0.35f
    }

    /** 「全部楼层」继续拉站点下一页并追加；成功后滚到新增楼层。 */
    private fun loadMoreFloors() {
        if (loadingMore || onlyOp || !hasMoreSitePages) return
        loadingMore = true
        renderFloorBar()
        val next = sitePage + 1
        lifecycleScope.launch {
            var appended = false
            try {
                val (pageData, _) = repo.getThreadPage(sourceId, tid, onlyOp = false, page = next)
                val existingFloors = posts.mapTo(HashSet()) { it.floor }
                val added = pageData.posts.filter { it.floor !in existingFloors }
                if (added.isNotEmpty()) {
                    posts = posts + added
                    sitePage = next
                    siteTotalPages = maxOf(pageData.totalPages, next)
                    hasMoreSitePages = sitePage < siteTotalPages
                    appended = true
                } else {
                    // 站点没有更多有效楼层（或返回了重复页）
                    siteTotalPages = maxOf(sitePage, minOf(siteTotalPages, next - 1))
                    hasMoreSitePages = false
                }
            } catch (e: Exception) {
                UiTips.snack(bind.root, getString(R.string.floor_load_failed, NetError.friendly(e)))
            } finally {
                loadingMore = false
                if (appended) renderPosts(scrollToBottom = true) else renderFloorBar()
            }
        }
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
