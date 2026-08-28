package com.example.litreader.ui.reader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
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
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.databinding.ActivityReaderBinding
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.ui.gallery.GalleryActivity
import kotlinx.coroutines.launch
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
    private var loaded = false
    private var restoredOnce = false
    private var menuShown = true
    private var finished = false

    private val themes = ReaderThemeSet.values()
    private val lineSpacings = floatArrayOf(1.6f, 1.9f, 2.2f)

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

    private val theme get() = themes[themeIdx]
    private val lineSpacing get() = lineSpacings[lineIdx]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(bind.root)
        repo = BookRepository((application as App).database)

        tid = intent.getStringExtra("tid") ?: return finish()
        sourceId = intent.getStringExtra("sourceId") ?: ""
        bind.tvTitle.text = intent.getStringExtra("title") ?: ""

        window.statusBarColor = Color.parseColor("#141414")
        setupMenu()
        setupContainers()
        applyThemeColors()
        load()
    }

    // ---------- 数据 ----------

    private fun load() {
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
        spannable = builder.build(posts)
        floorOffsets = builder.floorOffsets
        images = builder.imageUrls.toList()
        loaded = true
        bind.content.post {
            rebuild(restoreOffset = savedOffset(), restoreProgress = !restoredOnce)
        }
    }

    // ---------- 排版与分页 ----------

    private fun contentWidth(): Int = max(100, bind.content.width - dp(44))
    private fun contentHeight(): Int = max(100, bind.content.height - dp(70))

    private fun makeLayout(text: CharSequence, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, textPaint(), width)
            .setLineSpacing(0f, lineSpacing)
            .build()

    private fun textPaint(): TextPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = sp(fontSp())
        color = theme.fg
    }

    /** 重建排版并恢复到 restoreOffset 附近的页。 */
    private fun rebuild(restoreOffset: Int = currentOffset(), restoreProgress: Boolean = false) {
        if (!loaded) return
        val s = spannable ?: return
        applyThemeColors()
        layout = makeLayout(s, contentWidth())
        pages = Paginator.paginate(layout!!, contentHeight().toFloat())
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
        layout?.let { bind.pager.adapter = PagesAdapter(it, pages, pageCallbacks) { dp(it) } }
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
            saveProgress()
        }
    }

    private fun jumpToOffset(offset: Int) {
        if (pages.isEmpty()) return
        var idx = 0
        for (i in pages.indices) if (pages[i].startChar <= offset) idx = i
        bind.pager.setCurrentItem(idx, false)
        updatePageLabel()
        if (!restoredOnce && offset > 0) {
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
            bind.tvPagePos.text = getString(R.string.reader_scroll_pos)
        }
    }

    // ---------- 容器与手势 ----------

    private fun setupContainers() {
        bind.pager.visibility = View.GONE
        bind.scrollWrap.visibility = View.GONE

        bind.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updatePageLabel()
        })

        bind.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (flipMode) bind.pager.setCurrentItem(progress, false)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
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
                    toggleMenu()
                    return true
                }
            }
        )
        bind.scrollText.setOnTouchListener { _, e ->
            tapDetector.onTouchEvent(e)
            false
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
                renderFav()
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
            lineIdx = (lineIdx + 1) % lineSpacings.size
            bind.btnLine.text = getString(R.string.reader_line, trim(lineSpacing))
            rebuild()
        }
        bind.btnTheme.setOnClickListener {
            themeIdx = (themeIdx + 1) % themes.size
            bind.btnTheme.text = getString(R.string.reader_theme, theme.label)
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
        bind.btnFloorJump.setOnClickListener { showFloorJump() }
        bind.btnForum.setOnClickListener { goForum() }
        bind.btnFinished.setOnClickListener {
            finished = !finished
            lifecycleScope.launch { repo.setFinished(tid, finished) }
            renderFinishedLabel()
        }

        renderFav()
        renderFlipLabel()
        renderMarksLabel()
        renderFinishedLabel()
        bind.btnLine.text = getString(R.string.reader_line, trim(lineSpacing))
        bind.btnTheme.text = getString(R.string.reader_theme, theme.label)
        bind.tvFont.text = "${fontSp()}"
    }

    private fun setFont(v: Int) {
        val size = v.coerceIn(12, 30)
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
        val v = if (menuShown) View.VISIBLE else View.GONE
        bind.menuTop.visibility = v
        bind.menuBottom.visibility = v
        immersive(!menuShown)
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
        posPrefs.edit().putString("pos_$tid", "${currentOffset()}:${ratio.coerceIn(0f, 1f)}").apply()
    }

    // ---------- 工具 ----------

    private fun fontSp(): Int = fontPrefs.getInt("reader_font", 17)
    private fun sp(v: Int): Float = v * resources.displayMetrics.scaledDensity
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun trim(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive(!menuShown)
    }

    override fun onPause() {
        saveProgress()
        super.onPause()
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
        private val dpPx: (Int) -> Int
    ) : RecyclerView.Adapter<PagesAdapter.PVH>() {

        class PVH(val view: PageView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PVH {
            val view = PageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                setPadding(dpPx(22), dpPx(26), dpPx(22), dpPx(44))
                callbacks = this@PagesAdapter.callbacks
            }
            return PVH(view)
        }

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(h: PVH, position: Int) {
            h.view.bind(layout, pages[position])
        }
    }
}
