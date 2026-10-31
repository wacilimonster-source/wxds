package com.example.litreader.ui.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.model.Post
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.SourceStyle
import com.example.litreader.databinding.ActivityReaderBinding
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.ui.gallery.GalleryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.example.litreader.util.Haptics
import com.example.litreader.util.UiTips
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** 阅读主题（仅阅读器内部，独立于 App 日夜主题）。 */
enum class ReaderThemeSet(val bg: Int, val fg: Int, val subtle: Int, val label: String) {
    PAPER(0xFFFAF6EF.toInt(), 0xFF241E17.toInt(), 0xFF8A7F70.toInt(), "纸白"),
    SUEDE(0xFFF3E9D2.toInt(), 0xFF54432E.toInt(), 0xFF9C8B6E.toInt(), "羊皮"),
    GREEN(0xFFCCE8CF.toInt(), 0xFF23402B.toInt(), 0xFF6E8A74.toInt(), "护眼绿"),
    NIGHT(0xFF141414.toInt(), 0xFFBDB6A8.toInt(), 0xFF6E685C.toInt(), "夜间");
}

/**
 * 文学区沉浸阅读器：原生排版 + 左右翻页（可切上下滚动）+ 进度记忆。
 * 只读楼主内容；看回复请切「论坛模式」。
 */
class ReaderActivity : AppCompatActivity() {
    private lateinit var bind: ActivityReaderBinding
    private lateinit var repo: BookRepository

    private var tid = ""
    private var sourceId = ""
    private var favorite = false

    private var spannable: SpannableStringBuilder? = null
    private var layout: StaticLayout? = null
    private var pages: List<Paginator.Page> = emptyList()
    private var floorOffsets: Map<Int, Int> = emptyMap()
    private var images: List<String> = emptyList()
    private var imageSlots: List<ImageSlot> = emptyList()
    private var appliedImageUrls = mutableSetOf<String>()
    private var reflowJob: Job? = null
    // ---------- 正文搜索 ----------
    private var searchMatches: List<Int> = emptyList()
    private var searchIdx = -1
    private var searchTerm = ""
    private val searchSpans = ArrayList<BackgroundColorSpan>()
    private var searchCurrentSpan: BackgroundColorSpan? = null
    private var searchDialog: androidx.appcompat.app.AlertDialog? = null
    private var loaded = false
    private var restoredOnce = false
    /** 本次打开的目标恢复点：恢复完成前，任何尺寸变化/图片重排的 rebuild 都用它，避免被第 1 页覆盖。 */
    private var openRestoreOffset = -1
    private var menuShown = true
    private var finished = false

    private val themes = ReaderThemeSet.values()
    private val lineSpacings = floatArrayOf(1.6f, 1.9f, 2.2f)

    /** 正文边距预设：top, bottom, left, right（dp）。分页与绘制必须用同一组值。 */
    private val padPresets = arrayOf(
        intArrayOf(16, 36, 12, 12),  // 紧凑
        intArrayOf(30, 48, 22, 22),  // 标准（默认）
        intArrayOf(48, 64, 32, 32),  // 宽松
    )

    private val prefs2 by lazy { getSharedPreferences("reader2", MODE_PRIVATE) }
    private val fontPrefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private val posPrefs by lazy { getSharedPreferences("reader_pos", MODE_PRIVATE) }

    private var themeIdx: Int
        get() = prefs2.getInt("theme", 0).coerceIn(0, themes.size - 1)
        set(value) = prefs2.edit().putInt("theme", value).apply()
    private var lineIdx: Int
        get() = prefs2.getInt("line", 1).coerceIn(0, lineSpacings.size - 1)
        set(value) = prefs2.edit().putInt("line", value).apply()
    private var flipMode: Boolean
        get() = prefs2.getString("flip", "flip") != "scroll"
        set(value) = prefs2.edit().putString("flip", if (value) "flip" else "scroll").apply()
    private var floorMarks: Boolean
        get() = prefs2.getBoolean("marks", true)
        set(value) = prefs2.edit().putBoolean("marks", value).apply()
    private var autoFinish: Boolean
        get() = prefs2.getBoolean("autoFinish", false)
        set(value) = prefs2.edit().putBoolean("autoFinish", value).apply()
    private var volumeFlip: Boolean
        get() = prefs2.getBoolean("volumeFlip", true)
        set(value) = prefs2.edit().putBoolean("volumeFlip", value).apply()
    private var keepAwake: Boolean
        get() = prefs2.getBoolean("keepAwake", true)
        set(value) = prefs2.edit().putBoolean("keepAwake", value).apply()
    private var serif: Boolean
        get() = prefs2.getBoolean("serif", false)
        set(value) = prefs2.edit().putBoolean("serif", value).apply()
    /** 底部菜单面板：0=排版 1=更多 */
    private var menuTab: Int
        get() = prefs2.getInt("menuTab", 0)
        set(value) = prefs2.edit().putInt("menuTab", value).apply()
    private var padIdx: Int
        get() = prefs2.getInt("pad", 1).coerceIn(0, padPresets.size - 1)
        set(value) = prefs2.edit().putInt("pad", value).apply()

    private val theme get() = themes[themeIdx]
    private val lineSpacing get() = lineSpacings[lineIdx]
    private val pads get() = padPresets[padIdx].map { dp(it) }  // px: top, bottom, left, right

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(bind.root)
        repo = BookRepository((application as App).database)

        tid = intent.getStringExtra("tid") ?: return finish()
        sourceId = intent.getStringExtra("sourceId") ?: ""
        bind.tvTitle.text = intent.getStringExtra("title") ?: ""

        window.statusBarColor = Color.parseColor("#141414")
        // 进出转场：列表卡片位置放大淡入（近似 container-transform），返回时缩回
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, R.anim.lr_enter, R.anim.lr_stay)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.lr_enter, R.anim.lr_stay)
        }
        setupMenu()
        setupContainers()
        applyThemeColors()
        applyKeepAwake()
        load()
    }

    // ---------- 数据 ----------

    private fun load() {
        // 打开即记入「最近浏览」（行不存在时为 no-op；mock 行已入库，同样生效）
        lifecycleScope.launch { runCatching { repo.markOpened(tid) } }
        if (tid == MOCK_TID) {  // 视觉验收入口：tid=mock 用内置模拟正文，不联网
            buildContent(mockPosts())
            return
        }
        bind.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val (posts, _) = repo.getThread(sourceId, tid, true)
                if (posts.isEmpty()) {
                    showError(getString(R.string.reader_empty))
                } else {
                    favorite = repo.isFavorite(tid)
                    renderFav()
                    buildContent(posts)
                    // 打开即记为已读（不影响已读完标记）
                    repo.markRead(tid)
                    finished = repo.readState(tid) >= 2
                    renderFinishedLabel()
                }
            } catch (e: Exception) {
                showError(e.message ?: "网络错误")
            } finally {
                bind.progress.visibility = View.GONE
            }
        }
    }

    private fun buildContent(posts: List<com.example.litreader.data.model.Post>) {
        val builder = HtmlToSpans(theme.subtle, floorMarks, dp(22))
        lifecycleScope.launch {
            // 整帖 HTML→Spannable 解析较重（长帖数百 KB），放后台算，避免进阅读器掉帧
            val built = withContext(Dispatchers.Default) {
                BuiltContent(builder.build(posts), builder.floorOffsets, builder.imageUrls.toList(), builder.imageSlots)
            }
            spannable = built.spannable
            floorOffsets = built.floorOffsets
            images = built.images
            imageSlots = built.slots
            loaded = true
            openRestoreOffset = savedOffset()
            bind.content.post {
                rebuild(restoreOffset = savedOffset(), restoreProgress = !restoredOnce)
                requestInlineImages()
            }
        }
    }

    private data class BuiltContent(
        val spannable: SpannableStringBuilder,
        val floorOffsets: Map<Int, Int>,
        val images: List<String>,
        val slots: List<ImageSlot>
    )

    // ---------- 内联图片 ----------

    /** 按需加载正文图片，完成后挂 ImageSpan；多张先后到齐合并成一次重排。 */
    private fun requestInlineImages() {
        val s = spannable ?: return
        val targetW = contentWidth()
        imageSlots.filter { it.url !in appliedImageUrls }
            .groupBy { it.url }
            .forEach { (url, slots) ->
                InlineImages.load(this, url, targetW, lifecycleScope) { bmp ->
                    if (bmp == null) return@load  // 加载失败：保留占位块，仍可点击进画廊
                    appliedImageUrls += url
                    applyImage(s, url, slots, bmp)
                }
            }
    }

    private fun applyImage(s: SpannableStringBuilder, url: String, slots: List<ImageSlot>, bmp: Bitmap) {
        val targetW = contentWidth().coerceAtLeast(200)
        val maxH = contentHeight() * 3 / 4  // 图片行过高分页时必然独占一页，限制在页高 3/4 内
        var scale = targetW.toFloat() / bmp.width
        if (scale > 2f) scale = 2f  // 小图最多放大两倍，避免糊成马赛克
        if (bmp.height * scale > maxH) scale = maxH.toFloat() / bmp.height
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        var changed = false
        for (slot in slots) {
            if (slot.start >= s.length || s[slot.start] != '\uFFFC') continue
            s.getSpans(slot.start, slot.end, BackgroundColorSpan::class.java)
                .firstOrNull()?.let { s.removeSpan(it) }
            s.setSpan(
                InlineImageSpan(bmp, w, h, lineSpacing, textPaint().fontMetricsInt.let { it.descent - it.ascent }),
                slot.start, slot.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            changed = true
        }
        if (changed) scheduleReflow()
    }

    /** 图片高度改变行高，需重排；120ms 合并窗口避免多图连环闪页。 */
    private fun scheduleReflow() {
        reflowJob?.cancel()
        reflowJob = lifecycleScope.launch {
            delay(120)
            if (loaded) rebuild(restoreOffset = restoreOffsetForReflow())
        }
    }

    // ---------- 模拟数据（tid=mock 时渲染，边距/排版视觉验收用） ----------

    private fun mockPosts(): List<com.example.litreader.data.model.Post> {
        val a = "这一段模拟正文专门用来检查阅读器边距：上边距处首行不得顶进留白，下边距处末行不得侵入留白，左右留白需要对称，并在紧凑、标准、宽松三档之间切换时同步变化。翻页时上一页的最后一行不能残留在本页的上边距里，本页的第一行也不能探出到上一页的下边距里。"
        val b = "中文排版按字折行，无论一行以哪个字开头结尾，正文区域都应始终位于上下左右留白之内，页码与进度也按同一区域计算。段落之间的间距与楼层标记的位置同样不能越过留白边界。"
        val c = "短段落，仅一行。Here is Margin word."
        val mixed = "<p><b>粗体</b>与<i>斜体</i>与<u>下划线</u>与<font color=\"#8B2500\">朱砂色文字</font>混排，再放一张图片：<img src=\"http://127.0.0.1:8123/test.jpg\">图片之后继续一段普通文字，用来确认图片行前后的行高与边距没有异常跳变。再来一张竖图：<img src=\"http://127.0.0.1:8123/test2.jpg\">完。</p>"
        return listOf(
            Post(author = "排版验收", floor = 1, contentHtml = "<p>${a.repeat(3)}</p><p>$c</p>$mixed"),
            Post(author = "排版验收", floor = 2, contentHtml = "<blockquote>引用块：左右缩进叠加在正文边距内，整块使用弱化色，不得突破右侧留白。</blockquote><p>${b.repeat(4)}</p>"),
            Post(author = "排版验收", floor = 3, contentHtml = "<p>${a.repeat(2)}$b</p><p>$c</p><p>$c</p><p>$c</p>"),
            Post(author = "排版验收", floor = 4, contentHtml = "<p>${b.repeat(3)}${a.repeat(2)}</p>$mixed"),
            Post(author = "排版验收", floor = 5, contentHtml = "<p>${a.repeat(4)}</p><p>$c</p>"),
            Post(author = "排版验收", floor = 6, contentHtml = "<p>${b.repeat(2)}全文完。</p>"),
        )
    }

    // ---------- 排版与分页 ----------

    private fun contentWidth(): Int = max(100, bind.content.width - pads[2] - pads[3])
    private fun contentHeight(): Int = max(100, bind.content.height - pads[0] - pads[1])

    private fun makeLayout(text: CharSequence, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, textPaint(), width)
            .setLineSpacing(0f, lineSpacing)
            .build()

    private fun textPaint(): TextPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = sp(fontSp())
        color = theme.fg
        typeface = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
    }

    /** 恢复完成前一律回到本次打开的目标位置，防止尺寸变化把位置重置回第 1 页。 */
    private fun restoreOffsetForReflow(): Int =
        if (openRestoreOffset >= 0) openRestoreOffset else currentOffset()

    /** 重建排版并恢复到 restoreOffset 附近的页。 */
    private fun rebuild(restoreOffset: Int = currentOffset(), restoreProgress: Boolean = false) {
        if (!loaded) return
        val s = spannable ?: return
        applyThemeColors()
        layout = makeLayout(s, contentWidth())
        pages = Paginator.paginate(layout!!, contentHeight().toFloat(), textPaint().fontMetrics.descent)
        if (flipMode) {
            setupFlip(restoreOffset)
        } else {
            setupScroll(restoreOffset, restoreProgress)
        }
        updatePageLabel()
    }

    private fun setupFlip(restoreOffset: Int) {
        bind.pager.visibility = View.VISIBLE
        bind.scrollWrap.visibility = View.GONE
        // 覆盖式翻页：当前页在上层滑出，次页原地揭示（而非两页平移）；
        // 离场页以左缘为轴轻微缩小，模拟纸张掀起
        bind.pager.setPageTransformer { page, pos ->
            if (pos > 0f) {
                page.translationX = -pos * page.width
                page.translationZ = 0f
                page.scaleX = 1f
                page.scaleY = 1f
            } else {
                page.translationX = 0f
                page.translationZ = 1f
                page.pivotX = 0f
                val f = -pos
                page.scaleX = 1f - 0.04f * f
                page.scaleY = 1f - 0.04f * f
            }
        }
        layout?.let {
            bind.pager.adapter = PagesAdapter(
                it, pages, pageCallbacks,
                intArrayOf(pads[2], pads[0], pads[3], pads[1]),  // l, t, r, b
                theme.bg
            )
        }
        bind.pager.post {
            jumpToOffset(restoreOffset)
            saveProgress()
        }
    }

    private fun setupScroll(restoreOffset: Int, restoreProgress: Boolean) {
        bind.pager.visibility = View.GONE
        bind.scrollWrap.visibility = View.VISIBLE
        bind.scrollText.text = spannable
        bind.scrollText.textSize = fontSp().toFloat()
        bind.scrollText.setLineSpacing(0f, lineSpacing)
        bind.scrollText.setTextColor(theme.fg)
        bind.scrollText.typeface = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
        bind.scrollText.setPadding(pads[2], pads[0], pads[3], pads[1])
        bind.scrollWrap.post {
            if (restoreProgress && !restoredOnce) {
                val ratio = savedRatio()
                val h = max(1, bind.scrollText.height - bind.scrollWrap.height)
                bind.scrollWrap.scrollY = (ratio * h).toInt()
                if (ratio > 0.01f) toast(getString(R.string.reader_restored))
                restoredOnce = true
            } else {
                scrollToOffset(restoreOffset)
            }
            openRestoreOffset = -1
            saveProgress()
        }
    }

    private fun jumpToOffset(offset: Int, quiet: Boolean = false) {
        if (pages.isEmpty()) return
        var idx = 0
        for (i in pages.indices) if (pages[i].startChar <= offset) idx = i
        bind.pager.setCurrentItem(idx, false)
        openRestoreOffset = -1
        updatePageLabel()
        if (!quiet && !restoredOnce && offset > 0) {
            toast(getString(R.string.reader_restored))
            restoredOnce = true
        }
    }

    private fun scrollToOffset(offset: Int) {
        val l = bind.scrollText.layout ?: return
        val line = l.getLineForOffset(min(offset, l.text.length))
        bind.scrollWrap.scrollTo(0, l.getLineTop(line))
    }

    private fun currentOffset(): Int {
        if (flipMode) return pages.getOrNull(bind.pager.currentItem)?.startChar ?: 0
        val l = bind.scrollText.layout ?: return 0
        val y = bind.scrollWrap.scrollY + bind.scrollText.paddingTop
        return l.getLineStart(l.getLineForVertical(y))
    }

    private fun updatePageLabel() {
        if (flipMode && pages.isNotEmpty()) {
            bind.tvPagePos.text = getString(R.string.gallery_counter, bind.pager.currentItem + 1, pages.size)
            bind.seek.max = max(1, pages.size - 1)
            bind.seek.progress = bind.pager.currentItem
        } else {
            // 滚动模式：底部显示百分比进度，进度条可拖动定位
            val h = max(1, bind.scrollText.height - bind.scrollWrap.height)
            val ratio = (bind.scrollWrap.scrollY.toFloat() / h).coerceIn(0f, 1f)
            bind.tvPagePos.text = getString(R.string.reader_progress, (ratio * 100).toInt())
            bind.seek.max = 1000
            bind.seek.progress = (ratio * 1000).toInt()
        }
    }

    // ---------- 容器与手势 ----------

    private fun setupContainers() {
        bind.pager.visibility = View.GONE
        bind.scrollWrap.visibility = View.GONE

        // 导航栏隐藏/显示会改变内容区高度，必须按新尺寸重新分页，否则页尾多出一片死区
        bind.content.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, orr, ob ->
            if (loaded && v.width > 0 && v.height > 0 &&
                (r - l != orr - ol || b - t != ob - ot)
            ) {
                v.post { rebuild(restoreOffset = restoreOffsetForReflow()) }
            }
        }

        bind.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageLabel()
                maybeAutoFinish()
            }
        })

        bind.scrollWrap.setOnScrollChangeListener { _, _, _, _, _ ->
            updatePageLabel()
            maybeAutoFinish()
        }

        bind.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                updateSeekBubble(sb, progress)
                if (flipMode) {
                    bind.pager.setCurrentItem(progress, false)
                } else {
                    val h = max(1, bind.scrollText.height - bind.scrollWrap.height)
                    bind.scrollWrap.scrollTo(0, (progress / 1000f * h).toInt())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                bind.seekBubble.visibility = View.VISIBLE
                updateSeekBubble(sb, sb.progress)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                bind.seekBubble.visibility = View.GONE
                saveProgress()
            }
        })

        val tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val l = bind.scrollText.layout ?: return true
                    val text = l.text as? Spanned ?: return true
                    val line = l.getLineForVertical((e.y - bind.scrollText.paddingTop).toInt())
                    val off = l.getOffsetForHorizontal(line, e.x - bind.scrollText.paddingLeft)
                    val hit = text.getSpans(off, off, ImageLinkSpan::class.java).firstOrNull()
                    if (hit != null) {
                        openGallery(hit.url)
                        return true
                    }
                    // 与翻页模式一致的三区：左上滚、右下滚、中间呼出菜单
                    val w = bind.scrollText.width.toFloat()
                    when {
                        e.x < w / 3f -> movePage(-1)
                        e.x > w * 2f / 3f -> movePage(1)
                        else -> toggleMenu()
                    }
                    return true
                }
            }
        )
        // 必须消费事件：TextView 不可点击时 onTouchEvent 返回 false，DOWN 被拒后
        // UP 会改道 ScrollView，GestureDetector 凑不齐手势，中心点击永远无法呼出菜单
        bind.scrollText.setOnTouchListener { _, e ->
            tapDetector.onTouchEvent(e)
            true
        }
    }

    private fun movePage(delta: Int) {
        if (flipMode) {
            val target = bind.pager.currentItem + delta
            if (target in 0 until pages.size) bind.pager.setCurrentItem(target, true)
        } else {
            val h = max(1, bind.scrollWrap.height)
            bind.scrollWrap.smoothScrollBy(0, delta * h / 2)
        }
    }

    // ---------- 菜单 ----------

    private fun setupMenu() {
        bind.btnBack.setOnClickListener { finish() }
        bind.btnFav.setOnClickListener {
            lifecycleScope.launch {
                favorite = !favorite
                repo.setFavorite(tid, favorite)
                Haptics.key(bind.root)
                renderFav()
                UiTips.snack(
                    bind.root,
                    getString(if (favorite) R.string.fav_added else R.string.fav_removed),
                    anchor = if (menuShown) bind.menuBottom else null
                )
            }
        }
        bind.btnRetry.setOnClickListener {
            bind.errorBox.visibility = View.GONE
            load()
        }
        bind.btnErrorForum.setOnClickListener { goForum() }

        bind.btnFontDown.setOnClickListener { setFont(fontSp() - 1) }
        bind.btnFontUp.setOnClickListener { setFont(fontSp() + 1) }
        bind.btnLine.setOnClickListener {
            Haptics.tick(bind.root)
            lineIdx = (lineIdx + 1) % lineSpacings.size
            bind.btnLine.text = getString(R.string.reader_line, trim(lineSpacing))
            rebuild()
        }
        bind.btnTheme.setOnClickListener {
            setThemeIndex((themeIdx + 1) % themes.size)
        }
        bind.dotPaper.setOnClickListener { setThemeIndex(0) }
        bind.dotSuede.setOnClickListener { setThemeIndex(1) }
        bind.dotGreen.setOnClickListener { setThemeIndex(2) }
        bind.dotNight.setOnClickListener { setThemeIndex(3) }
        bind.btnPad.setOnClickListener {
            Haptics.tick(bind.root)
            padIdx = (padIdx + 1) % padPresets.size
            renderPadLabel()
            rebuild()
        }
        bind.btnFlip.setOnClickListener {
            val cur = currentOffset()
            flipMode = !flipMode
            renderFlipLabel()
            rebuild(restoreOffset = cur)
        }
        bind.btnMarks.setOnClickListener {
            floorMarks = !floorMarks
            renderMarksLabel()
            rebuild()
        }
        bind.btnAutoFinish.setOnClickListener {
            autoFinish = !autoFinish
            renderAutoFinishLabel()
        }
        bind.btnVolume.setOnClickListener {
            volumeFlip = !volumeFlip
            renderVolumeLabel()
        }
        bind.btnAwake.setOnClickListener {
            keepAwake = !keepAwake
            applyKeepAwake()
            renderAwakeLabel()
        }
        bind.btnSearch.setOnClickListener { showSearchDialog() }
        bind.btnFontFamily.setOnClickListener {
            Haptics.tick(bind.root)
            serif = !serif
            renderFontFamilyLabel()
            rebuild()
        }
        bind.menuTabStyle.setOnClickListener { menuTab = 0; renderMenuTab() }
        bind.menuTabMore.setOnClickListener { menuTab = 1; renderMenuTab() }
        bind.btnForum.text = "论坛"  // 短文案避免窄屏换行
        // 图片帖已在原生视图（内联图+点图进画廊），无需跳 WebView 楼层视图
        if (SourceRegistry.get(sourceId)?.style == SourceStyle.IMAGE) {
            bind.btnForum.visibility = View.GONE
        }
        renderMenuTab()
        bind.btnFloorJump.setOnClickListener { showFloorJump() }
        bind.btnForum.setOnClickListener { goForum() }
        bind.btnFinished.setOnClickListener {
            finished = !finished
            if (finished) Haptics.confirm(bind.root)
            lifecycleScope.launch { repo.setFinished(tid, finished) }
            renderFinishedLabel()
        }

        renderFav()
        renderThemeDots()
        renderFlipLabel()
        renderMarksLabel()
        renderAutoFinishLabel()
        renderVolumeLabel()
        renderAwakeLabel()
        renderFontFamilyLabel()
        renderFinishedLabel()
        bind.btnLine.text = getString(R.string.reader_line, trim(lineSpacing))
        bind.btnTheme.text = getString(R.string.reader_theme, theme.label)
        renderPadLabel()
        bind.tvFont.text = "${fontSp()}"
    }

    private fun setFont(v: Int) {
        val size = v.coerceIn(12, 30)
        if (size == fontSp()) return
        Haptics.tick(bind.root)
        fontPrefs.edit().putInt("reader_font", size).apply()
        bind.tvFont.text = "$size"
        rebuild()
    }

    private fun showFloorJump() {
        if (floorOffsets.isEmpty()) return
        val floors = floorOffsets.keys.sorted()
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "${floors.first()} - ${floors.last()}"
        }
        val wrap = FrameLayout(this).apply {
            val pad = dp(20)
            setPadding(pad, dp(10), pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.reader_floor_jump))
            .setView(wrap)
            .setPositiveButton(R.string.jump_go) { _, _ ->
                val f = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                val target = floorForJump(f) ?: return@setPositiveButton
                if (flipMode) jumpToOffset(target) else scrollToOffset(target)
                saveProgress()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun floorForJump(f: Int): Int? =
        floorOffsets[f] ?: floorOffsets.keys.filter { it <= f }.maxOrNull()?.let { floorOffsets[it] }

    // ---------- 正文搜索 ----------

    private fun showSearchDialog() {
        if (!loaded) return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            hint = getString(R.string.reader_search_hint)
            setText(searchTerm)
        }
        val wrap = FrameLayout(this).apply {
            val pad = dp(20)
            setPadding(pad, dp(10), pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.reader_search_title))
            .setView(wrap)
            .setPositiveButton(R.string.reader_search_next, null)
            .setNegativeButton(R.string.reader_search_prev, null)
            .setNeutralButton(R.string.cancel, null)
            .create()
        searchDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (ensureSearch(input)) navigateMatch(1)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (ensureSearch(input)) navigateMatch(-1)
            }
            input.setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER
                if (enter && ensureSearch(input)) navigateMatch(1)
                enter
            }
        }
        dialog.setOnDismissListener {
            searchDialog = null
            clearSearchSpans()
            if (loaded) rebuild(restoreOffset = restoreOffsetForReflow())
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    /** 关键词变化则重新搜索；返回是否存在匹配。 */
    private fun ensureSearch(input: EditText): Boolean {
        val term = input.text.toString().trim()
        if (term.isEmpty()) return false
        if (term != searchTerm) doSearch(term)
        return searchMatches.isNotEmpty()
    }

    private fun doSearch(term: String) {
        val s = spannable ?: return
        clearSearchSpans()
        searchTerm = term
        val hay = s.toString().lowercase()
        val needle = term.lowercase()
        val found = ArrayList<Int>()
        var i = hay.indexOf(needle)
        while (i >= 0) {
            found.add(i)
            val span = BackgroundColorSpan(0x66FFC107)
            s.setSpan(span, i, i + needle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            searchSpans.add(span)
            i = hay.indexOf(needle, i + needle.length)
        }
        searchMatches = found
        if (found.isEmpty()) {
            searchIdx = -1
            searchDialog?.setTitle(getString(R.string.reader_search_none, term))
            return
        }
        val cur = currentOffset()
        searchIdx = found.indexOfFirst { it >= cur }.let { if (it >= 0) it else 0 }
        jumpToMatch()
    }

    /** 当前匹配高亮加深并跳到该页；span 变化通过 rebuild 触发重绘（setupFlip 内部会跳回目标页）。 */
    private fun jumpToMatch() {
        if (searchIdx !in searchMatches.indices) return
        val s = spannable ?: return
        val start = searchMatches[searchIdx]
        val end = start + searchTerm.length
        searchCurrentSpan?.let { s.removeSpan(it) }
        searchCurrentSpan = BackgroundColorSpan(0xCCFF7043.toInt()).also {
            s.setSpan(it, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        searchDialog?.setTitle(getString(R.string.reader_search_count, searchMatches.size, searchIdx + 1))
        jumpToOffset(start, quiet = true)
        rebuild(restoreOffset = start)
    }

    private fun navigateMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        searchIdx = (searchIdx + delta + searchMatches.size) % searchMatches.size
        jumpToMatch()
    }

    private fun clearSearchSpans() {
        val s = spannable ?: return
        searchSpans.forEach { s.removeSpan(it) }
        searchCurrentSpan?.let { s.removeSpan(it) }
        searchSpans.clear()
        searchCurrentSpan = null
        searchMatches = emptyList()
        searchIdx = -1
    }

    private fun goForum() {
        startActivity(Intent(this, ThreadDetailActivity::class.java).apply {
            putExtra("tid", tid)
            putExtra("title", intent.getStringExtra("title"))
            putExtra("sourceId", sourceId)
        })
        finish()
    }

    private fun openGallery(url: String) {
        val list = if (images.contains(url)) images else images + url
        startActivity(Intent(this, GalleryActivity::class.java).apply {
            putStringArrayListExtra("urls", ArrayList(list))
            putExtra("index", list.indexOf(url))
            putExtra("title", bind.tvTitle.text.toString())
        })
    }

    private fun toggleMenu() {
        menuShown = !menuShown
        animateMenu()
        immersive(!menuShown)
    }

    /** 菜单呼出/隐藏：顶栏上滑、底板下滑，200ms 标准缓动。 */
    private fun animateMenu() {
        val top = bind.menuTop
        val bottom = bind.menuBottom
        val dur = 200L
        if (menuShown) {
            top.visibility = View.VISIBLE
            bottom.visibility = View.VISIBLE
            top.translationY = -top.height * 0.6f - 1f
            bottom.translationY = bottom.height * 0.6f + 1f
            top.alpha = 0f
            bottom.alpha = 0f
            top.animate().translationY(0f).alpha(1f).setDuration(dur)
                .setInterpolator(DecelerateInterpolator()).start()
            bottom.animate().translationY(0f).alpha(1f).setDuration(dur)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            top.animate().translationY(-top.height * 0.6f - 1f).alpha(0f).setDuration(dur)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { top.visibility = View.GONE }.start()
            bottom.animate().translationY(bottom.height * 0.6f + 1f).alpha(0f).setDuration(dur)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { bottom.visibility = View.GONE }.start()
        }
    }

    private fun immersive(hide: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun applyThemeColors() {
        bind.root.setBackgroundColor(theme.bg)
        bind.tvError.setTextColor(theme.subtle)
    }

    private fun renderFav() {
        bind.btnFav.setImageResource(
            if (favorite) R.drawable.ic_star_fill else R.drawable.ic_star_outline
        )
    }

    private fun renderFlipLabel() {
        bind.btnFlip.text = getString(
            R.string.reader_flip,
            getString(if (flipMode) R.string.reader_flip_lr else R.string.reader_scroll)
        )
    }

    private fun renderPadLabel() {
        val label = getString(
            when (padIdx) {
                0 -> R.string.reader_pad_tight
                1 -> R.string.reader_pad_normal
                else -> R.string.reader_pad_loose
            }
        )
        bind.btnPad.text = getString(R.string.reader_pad, label)
    }

    private fun renderMarksLabel() {
        bind.btnMarks.text = getString(
            R.string.reader_marks, getString(if (floorMarks) R.string.on else R.string.off)
        )
    }

    private fun renderFinishedLabel() {
        bind.btnFinished.text = getString(
            if (finished) R.string.reader_finished else R.string.reader_finish_mark
        )
    }

    private fun renderAutoFinishLabel() {
        bind.btnAutoFinish.text = getString(
            R.string.reader_auto, getString(if (autoFinish) R.string.on else R.string.off)
        )
    }

    private fun renderVolumeLabel() {
        bind.btnVolume.text = getString(
            R.string.reader_volume, getString(if (volumeFlip) R.string.on else R.string.off)
        )
    }

    private fun renderAwakeLabel() {
        bind.btnAwake.text = getString(
            R.string.reader_awake, getString(if (keepAwake) R.string.on else R.string.off)
        )
    }

    private fun renderFontFamilyLabel() {
        bind.btnFontFamily.text = getString(
            R.string.reader_font_family, getString(if (serif) R.string.font_serif else R.string.font_sans)
        )
    }

    /** 主题色卡点选：即点即换即存，选中圈朱砂描边。 */
    private fun setThemeIndex(idx: Int) {
        if (themeIdx == idx) return
        Haptics.tick(bind.root)
        themeIdx = idx
        bind.btnTheme.text = getString(R.string.reader_theme, theme.label)
        renderThemeDots()
        rebuild()
    }

    private fun renderThemeDots() {
        bind.dotPaper.isSelected = themeIdx == 0
        bind.dotSuede.isSelected = themeIdx == 1
        bind.dotGreen.isSelected = themeIdx == 2
        bind.dotNight.isSelected = themeIdx == 3
    }

    /** 拖动进度条时的页码气泡：跟随 thumb 位置。 */
    private fun updateSeekBubble(sb: SeekBar, progress: Int) {
        bind.seekBubble.text = if (flipMode) "${progress + 1} 页" else "${(progress / 10f).toInt()}%"
        bind.seekBubble.post {
            if (isFinishing || isDestroyed) return@post
            val usable = (sb.width - sb.paddingLeft - sb.paddingRight).coerceAtLeast(1)
            val x = sb.paddingLeft + usable * progress.toFloat() / sb.max.coerceAtLeast(1)
            val maxX = (sb.width - bind.seekBubble.width).toFloat().coerceAtLeast(0f)
            bind.seekBubble.translationX = (x - bind.seekBubble.width / 2f).coerceIn(0f, maxX)
        }
    }

    /** 底部菜单两面板切换：选中 tab 下划线朱砂。 */
    private fun renderMenuTab() {
        val styleSel = menuTab == 0
        bind.menuTabStyle.isSelected = styleSel
        bind.menuTabStyle.setTextColor(
            getColor(if (styleSel) R.color.galleryOnDark else R.color.gallerySubtle)
        )
        bind.menuTabMore.isSelected = !styleSel
        bind.menuTabMore.setTextColor(
            getColor(if (!styleSel) R.color.galleryOnDark else R.color.gallerySubtle)
        )
        bind.panelStyle.visibility = if (styleSel) View.VISIBLE else View.GONE
        bind.panelMore.visibility = if (styleSel) View.GONE else View.VISIBLE
    }

    /** 阅读时常亮：窗口级 FLAG，离开阅读器自动失效。 */
    private fun applyKeepAwake() {
        if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 音量键翻页：翻页模式翻页，滚动模式滚一屏（按住连发）。 */
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (volumeFlip && loaded && searchDialog?.isShowing != true) {
            when (ev.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> if (ev.action == KeyEvent.ACTION_DOWN) { movePage(-1); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> if (ev.action == KeyEvent.ACTION_DOWN) { movePage(1); return true }
            }
        }
        return super.dispatchKeyEvent(ev)
    }

    /** 自动读完：翻/滚到最后一页且开关打开时标记已读完（仅一次，toast 提示）。 */
    private fun maybeAutoFinish() {
        if (!autoFinish || !loaded || finished) return
        val atEnd = if (flipMode) {
            pages.isNotEmpty() && bind.pager.currentItem == pages.size - 1
        } else {
            val h = max(1, bind.scrollText.height - bind.scrollWrap.height)
            bind.scrollWrap.scrollY >= h - dp(40)
        }
        if (atEnd) {
            finished = true
            lifecycleScope.launch { repo.setFinished(tid, true) }
            renderFinishedLabel()
            toast(getString(R.string.reader_auto_finished))
        }
    }

    private fun showError(msg: String) {
        loaded = false
        bind.tvError.text = msg
        bind.errorBox.visibility = View.VISIBLE
    }

    // ---------- 进度 ----------

    private fun savedOffset(): Int =
        posPrefs.getString("pos_$tid", null)?.split(":")?.getOrNull(0)?.toIntOrNull() ?: 0

    private fun savedRatio(): Float =
        posPrefs.getString("pos_$tid", null)?.split(":")?.getOrNull(1)?.toFloatOrNull() ?: 0f

    private fun saveProgress() {
        if (!loaded) return
        val ratio = when {
            flipMode -> if (pages.isEmpty()) 0f else bind.pager.currentItem.toFloat() / pages.size
            else -> {
                val h = max(1, bind.scrollText.height - bind.scrollWrap.height)
                bind.scrollWrap.scrollY.toFloat() / h
            }
        }
        val edit = posPrefs.edit()
            .putString("pos_$tid", "${currentOffset()}:${ratio.coerceIn(0f, 1f)}")
        if (ratio > 0.005f) {
            // 供首页「继续阅读」卡片：最近一次真正开始阅读的帖子
            edit.putString("last", "$tid\u0001$sourceId\u0001${bind.tvTitle.text}\u0001${ratio.coerceIn(0f, 1f)}")
        }
        edit.apply()
    }

    // ---------- 工具 ----------

    private fun fontSp(): Int = fontPrefs.getInt("reader_font", 17)
    private fun sp(v: Int): Float = v * resources.displayMetrics.scaledDensity
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun trim(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    private fun toast(msg: String) =
        UiTips.snack(bind.root, msg, anchor = if (menuShown) bind.menuBottom else null)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive(!menuShown)
    }

    override fun onPause() {
        saveProgress()
        super.onPause()
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

    private val pageCallbacks = object : PageView.Callbacks {
        override fun onZoneTap(isNext: Boolean) = movePage(if (isNext) 1 else -1)
        override fun onCenterTap() = toggleMenu()
        override fun onImageTap(url: String) = openGallery(url)
    }

    private class PagesAdapter(
        private val layout: StaticLayout,
        private val pages: List<Paginator.Page>,
        private val callbacks: PageView.Callbacks,
        private val padsPx: IntArray,  // l, t, r, b
        private val bgColor: Int       // 页面必须不透明：覆盖式翻页靠上层页遮住钉在脚下的次页
    ) : RecyclerView.Adapter<PagesAdapter.PVH>() {

        class PVH(val view: PageView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PVH {
            val view = PageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                setPadding(padsPx[0], padsPx[1], padsPx[2], padsPx[3])
                setBackgroundColor(bgColor)
                callbacks = this@PagesAdapter.callbacks
            }
            return PVH(view)
        }

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(h: PVH, position: Int) {
            h.view.bind(layout, pages[position])
        }
    }

    companion object {
        /** tid=mock 时渲染内置模拟正文（边距/排版视觉验收，不联网）。 */
        const val MOCK_TID = "mock"
    }
}
