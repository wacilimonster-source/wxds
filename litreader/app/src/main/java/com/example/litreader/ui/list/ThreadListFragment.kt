package com.example.litreader.ui.list

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.TagCount
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.repo.MarkSnapshot
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.SourceStyle
import com.example.litreader.databinding.FragmentListBinding
import com.example.litreader.util.Haptics
import com.example.litreader.util.ReadProgress
import com.example.litreader.util.Skeleton
import com.example.litreader.util.UiTips
import com.example.litreader.util.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 文学/贴图共用的列表页，按 sourceId 区分数据源。固定顶栏，阅读状态收进筛选面板。 */
class ThreadListFragment : Fragment() {
    private lateinit var bind: FragmentListBinding
    private lateinit var vm: ThreadListViewModel
    private lateinit var adapter: ThreadAdapter
    private var sourceId: String = ""
    private var syncVm: CatalogSyncViewModel? = null
    private var lastObservedRunning: Boolean? = null
    /** 首轮目录同步是否进行中：空列表时展示「同步中」而不是误导性的「还没有本地目录」。 */
    private var syncRunning = false
    private var isText = false
    /** 已读比例表：只在进入/返回时刷新，滚动追加页时复用，避免主线程反复读 prefs。 */
    private var progressMap: Map<String, Float> = emptyMap()
    /** 标签 chips 只在数据或选中项变化时重建，避免每次筛选都销毁重造一批 TextView。 */
    private var renderedTags: List<TagCount>? = null
    private var renderedTag = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceId = arguments?.getString(ARG_SOURCE) ?: SourceRegistry.first().id
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        bind = FragmentListBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val source = SourceRegistry.get(sourceId) ?: SourceRegistry.first()
        bind.tvTitle.text = source.name

        vm = ViewModelProvider(
            this,
            ThreadListVmFactory(
                (requireActivity().application as App).database,
                requireActivity().getSharedPreferences("catalog", Context.MODE_PRIVATE),
                source.id
            )
        )[ThreadListViewModel::class.java]
        adapter = ThreadAdapter(
            { open(it) }, { onLongPress(it) },
            onFavorite = { onFavoriteToggle(it) },
            onDislike = { onDislikeToggle(it) },
            showThumb = source.style == SourceStyle.IMAGE,
            withFooter = true,
            onRetry = { vm.retryAppend() }
        )
        bind.recycler.layoutManager = LinearLayoutManager(requireContext())
        bind.recycler.setHasFixedSize(true)
        bind.recycler.adapter = adapter
        setupSwipe()
        progressMap = ReadProgress.map(requireContext())

        isText = source.style == SourceStyle.TEXT
        if (isText) {
            // 文学区：目录全量落库 + 增量同步（MainActivity 进 App 已触发，此处共享同一 VM）
            syncVm = ViewModelProvider(
                requireActivity(),
                CatalogSyncVmFactory(
                    (requireActivity().application as App).database,
                    requireActivity().getSharedPreferences("catalog", Context.MODE_PRIVATE),
                    source.id
                )
            )[CatalogSyncViewModel::class.java]
            syncVm?.state?.observe(viewLifecycleOwner, ::renderSync)
        } else {
            bind.tvSyncStatus.visibility = View.GONE
        }
        renderFilterButton()

        bind.swipe.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.accent))
        bind.swipe.setOnRefreshListener { onManualRefresh() }
        bind.tvPage.setOnClickListener { showJumpSheet() }
        bind.tvPage.contentDescription = getString(R.string.jump_sheet_title)
        bind.btnSearch.setOnClickListener {
            (requireActivity() as? com.example.litreader.ui.main.MainActivity)?.openSearch(source.id, focus = true)
        }
        bind.btnMore.setOnClickListener { showMoreMenu(bind.btnMore) }
        bind.btnFilter.setOnClickListener {
            FilterSheet.newInstance(vm.readFilter) { applied ->
                vm.applyReadFilter(applied)
                renderFilterButton()
            }.show(parentFragmentManager, "filter")
        }

        // 无限滚动：距底部 6 项时自动追加下一页
        bind.recycler.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (dy <= 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) {
                    // loadMore 会立即发布 appending，observer 里的 footer notify 若在
                    // onScrolled 内同步执行，会触发 RecyclerView 的 “computing layout” 异常。
                    rv.post { vm.loadMore() }
                }
            }
        })

        vm.threads.observe(viewLifecycleOwner) { list ->
            adapter.submit(list, progressMap)
            renderEmpty()
            renderPageLabel()
            updateFooter()
        }
        vm.tags.observe(viewLifecycleOwner) { renderTags(it) }
        vm.firstLoading.observe(viewLifecycleOwner) { first ->
            if (first) {
                Skeleton.show(bind.skeleton.root)
                bind.emptyBox.visibility = View.GONE
            } else {
                Skeleton.hide(bind.skeleton.root)
                renderEmpty()
            }
        }
        vm.loading.observe(viewLifecycleOwner) { loading ->
            if (!loading) bind.swipe.isRefreshing = false
            renderEmpty()
            renderPageLabel()
            updateFooter()
        }
        vm.appending.observe(viewLifecycleOwner) { updateFooter() }
        vm.appendError.observe(viewLifecycleOwner) { updateFooter() }
        vm.error.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                UiTips.snack(bind.root, msg, anchor = bind.tvPage)
                vm.error  // 值保留到下一次；此处只展示
            }
        }
        vm.opFailure.observe(viewLifecycleOwner) { failed ->
            if (failed) {
                UiTips.snack(bind.root, getString(R.string.op_failed), anchor = bind.tvPage)
                vm.consumeOpFailure()
            }
        }
        vm.archivedNotice.observe(viewLifecycleOwner) { valid ->
            if (valid > 0) {
                UiTips.snack(bind.root, getString(R.string.gallery_archived, valid), anchor = bind.tvPage, long = true)
                vm.clearArchivedNotice()
            }
        }

        vm.load(1)
    }

    /** 尾部状态：失败重试 > 加载中 > 到底 > 无。 */
    private fun updateFooter() {
        val state = when {
            vm.appendError.value == true -> ThreadAdapter.FooterState.RETRY
            vm.appending.value == true -> ThreadAdapter.FooterState.LOADING
            vm.noMore && !vm.threads.value.isNullOrEmpty() -> ThreadAdapter.FooterState.END
            else -> ThreadAdapter.FooterState.NONE
        }
        adapter.setFooter(state)
    }

    /** 空态分型：筛选零结果（可清除）/ 无目录（可同步）/ 网络失败（可重试）/ 常规空。 */
    private fun renderEmpty() {
        val empty = vm.threads.value.isNullOrEmpty()
        val first = vm.firstLoading.value == true
        val loading = vm.loading.value == true
        bind.emptyBox.visibility = if (empty && !loading && !first) View.VISIBLE else View.GONE
        bind.swipe.visibility = if (empty && !first) View.GONE else View.VISIBLE
        if (!empty || loading || first) return
        if (syncRunning) {
            bind.tvEmptyTitle.setText(R.string.empty_syncing_title)
            bind.tvEmptyHint.setText(R.string.empty_syncing_hint)
            bind.btnEmptyClear.visibility = View.GONE
            bind.btnEmptyAction.visibility = View.GONE
            bind.btnEmptyAction.setOnClickListener(null)
            return
        }
        when (vm.emptyReason.value) {
            ThreadListViewModel.EmptyReason.FILTERED -> {
                bind.tvEmptyTitle.setText(R.string.empty_filtered_title)
                bind.tvEmptyHint.setText(R.string.empty_filtered_hint)
                bind.btnEmptyClear.visibility = View.VISIBLE
                bind.btnEmptyAction.visibility = View.GONE
                bind.btnEmptyAction.setOnClickListener(null)
            }
            ThreadListViewModel.EmptyReason.NO_CATALOG -> {
                bind.tvEmptyTitle.setText(R.string.empty_no_catalog_title)
                bind.tvEmptyHint.setText(R.string.empty_no_catalog_hint)
                bind.btnEmptyClear.visibility = View.GONE
                bind.btnEmptyAction.setText(R.string.sync_catalog)
                bind.btnEmptyAction.visibility = View.VISIBLE
                bind.btnEmptyAction.setOnClickListener { syncVm?.sync() ?: vm.refresh() }
            }
            ThreadListViewModel.EmptyReason.NETWORK -> {
                bind.tvEmptyTitle.setText(R.string.empty_network_title)
                bind.tvEmptyHint.setText(R.string.empty_network_hint)
                bind.btnEmptyClear.visibility = View.GONE
                bind.btnEmptyAction.setText(R.string.empty_action_refresh)
                bind.btnEmptyAction.visibility = View.VISIBLE
                bind.btnEmptyAction.setOnClickListener { vm.refresh() }
            }
            null -> {
                bind.tvEmptyTitle.setText(
                    if (isText) R.string.empty_title else R.string.empty_gallery_title
                )
                bind.tvEmptyHint.setText(
                    if (isText) R.string.empty_hint else R.string.empty_gallery_hint
                )
                bind.btnEmptyClear.visibility = View.GONE
                bind.btnEmptyAction.setText(R.string.empty_action_refresh)
                bind.btnEmptyAction.visibility = View.VISIBLE
                bind.btnEmptyAction.setOnClickListener { onManualRefresh() }
            }
        }
    }

    /** 筛选按钮：应用中的阅读状态数量（0/1）。 */
    private fun renderFilterButton() {
        val n = if (vm.readFilter == -1) 0 else 1
        bind.btnFilter.text = if (n > 0) getString(R.string.filter_count_n, n) else getString(R.string.filter_sheet)
    }

    private var firstResume = true

    override fun onResume() {
        super.onResume()
        // 首次进入由 vm.load(1) 出数据；从阅读器/详情返回时只刷新进度表和变动的那一条，
        // 不再整页重查（已加载深页时，这一步会拖慢返回动画后的首帧）。
        if (firstResume) {
            firstResume = false
            return
        }
        if (!this::vm.isInitialized) return
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val map = withContext(Dispatchers.IO) { ReadProgress.map(appCtx) }
            val dirty = withContext(Dispatchers.IO) { ReadProgress.consumeDirty(appCtx, sourceId) }
            val changedProgress = map != progressMap
            progressMap = map
            val cur = vm.threads.value.orEmpty()
            // 进度变化（读到 N%）也要重新绑定；没变化就不跑 DiffUtil
            if (changedProgress && cur.isNotEmpty()) adapter.submit(cur, map)
            if (dirty != null) vm.refreshThread(dirty)
        }
    }

    /** 手动刷新：文学区走目录增量同步（后台，不阻塞导航），贴图区回到第 1 页强制重抓。 */
    private fun onManualRefresh() {
        val sync = syncVm
        if (sync != null) {
            vm.load(1)
            sync.sync()
        } else {
            vm.refresh()
        }
    }

    private fun renderSync(st: CatalogSyncViewModel.SyncState) {
        syncRunning = st.running
        if (st.running) {
            bind.tvSyncStatus.visibility = View.VISIBLE
            bind.tvSyncStatus.text = getString(
                R.string.sync_progress, st.page, maxOf(st.totalPages, st.page), st.totalNew
            )
        } else {
            bind.tvSyncStatus.visibility = View.GONE
            if (lastObservedRunning == true) {
                // 同步刚结束
                if (st.error != null) {
                    UiTips.snack(
                        bind.root, getString(R.string.sync_failed_cached, st.error), anchor = bind.tvPage
                    )
                }
                vm.reloadLoadedPages()
            } else if (lastObservedRunning == null && st.error == null) {
                // 进入页面时同步已完成（粘性状态）→ 直接读一次库
                vm.reloadLoadedPages()
            }
        }
        lastObservedRunning = st.running
        if (vm.threads.value.isNullOrEmpty()) renderEmpty()
    }

    /** 标签筛选 chips：全部 + 目录里实际出现的标签（带数量），触摸高 48dp。 */
    private fun renderTags(counts: List<TagCount>) {
        if (counts == renderedTags && vm.tag == renderedTag) return
        renderedTags = counts
        renderedTag = vm.tag
        bind.tagBar.visibility = View.VISIBLE
        val row = bind.tagRow
        row.removeAllViews()
        addChip(row, getString(R.string.filter_all), vm.tag.isEmpty()) {
            if (vm.tag.isNotEmpty()) {
                vm.selectTag("")
                renderTags(vm.tags.value.orEmpty())
            }
        }
        counts.forEach { tc ->
            addChip(row, "${tc.tag} ${tc.n}", vm.tag == tc.tag) {
                if (vm.tag != tc.tag) {
                    vm.selectTag(tc.tag)
                    renderTags(vm.tags.value.orEmpty())
                }
            }
        }
    }

    /** 统一胶囊 chip：描边底 → 选中 soft 填充（isSelected 驱动），点击轻震。 */
    private fun addChip(
        row: LinearLayout,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val tv = TextView(requireContext(), null, 0, R.style.LitChip).apply {
            text = label
            isSelected = selected
            minHeight = dp(48)
            gravity = Gravity.CENTER
            setOnClickListener {
                Haptics.tick(this)
                onClick()
            }
        }
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
        ).apply { marginEnd = dp(8) }
        row.addView(tv)
    }

    /** 跳页：底部弹层，页码宫格直达 + 输入兜底；总页数未知禁用提交。 */
    private fun showJumpSheet() {
        JumpSheet.newInstance(vm.totalPages, vm.page, vm.totalKnown) { n -> vm.load(n) }
            .show(parentFragmentManager, "jump")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)
        popup.menu.add(0, MENU_VERSION, 0, getString(R.string.current_version, com.example.litreader.util.Constants.VERSION_NAME))
            .isEnabled = false
        popup.menu.add(0, MENU_REFRESH, 1, getString(R.string.refresh))
        val blocked = vm.blockedCount()
        popup.menu.add(0, MENU_KEYWORDS, 2,
            if (blocked > 0) getString(R.string.keyword_block_n, blocked) else getString(R.string.keyword_block)
        )
        popup.menu.add(0, MENU_CHECK_UPDATE, 3, getString(R.string.check_update))
        popup.menu.add(0, MENU_CRASH_LOGS, 4, getString(R.string.crash_logs))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REFRESH -> {
                    onManualRefresh()
                    true
                }
                MENU_KEYWORDS -> {
                    KeywordSheet.newInstance(sourceId) {
                        vm.refilter()
                    }.show(parentFragmentManager, "keywords")
                    true
                }
                MENU_CHECK_UPDATE -> {
                    UpdateManager.checkAndPromptUpdate(requireActivity(), lifecycleScope, silent = false)
                    true
                }
                MENU_CRASH_LOGS -> {
                    showCrashLogs()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** 不喜欢按钮：状态翻转 + 可撤销（快照恢复标记前收藏态）。 */
    private fun onDislikeToggle(t: ThreadEntity) {
        val target = !t.dislike
        if (target) Haptics.confirm(bind.root) else Haptics.tick(bind.root)
        vm.setDislike(t, target) { snap, v ->
            undoSnack(t.tid, snap, v, getString(if (target) R.string.dislike_marked else R.string.dislike_removed))
        }
    }

    private fun undoSnack(tid: String, snap: MarkSnapshot, version: Int, text: String) {
        UiTips.snack(
            bind.root, text, getString(R.string.undo),
            onAction = { vm.undoMark(tid, snap, version) },
            anchor = bind.tvPage
        )
    }

    /** 收藏按钮：点星不进正文；状态翻转 + 可撤销。 */
    private fun onFavoriteToggle(t: ThreadEntity) {
        Haptics.tick(bind.root)
        val target = !t.favorite
        vm.setFavorite(t, target) { snap, v ->
            undoSnack(t.tid, snap, v, getString(if (target) R.string.fav_added else R.string.fav_removed))
        }
    }

    /** 本地崩溃日志列表（App 未捕获异常落盘，最多 5 份）。 */
    private fun showCrashLogs() {
        val files = requireContext().getExternalFilesDir("crash")
            ?.listFiles()?.sortedByDescending { it.name }.orEmpty()
        if (files.isEmpty()) {
            UiTips.snack(bind.root, getString(R.string.crash_logs_empty))
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.crash_logs)
            .setItems(files.map { it.name }.toTypedArray()) { _, which -> showCrashContent(files[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCrashContent(file: File) {
        val content = runCatching { file.readText() }.getOrDefault("读取失败")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val scroll = ScrollView(requireContext())
        val tv = TextView(requireContext()).apply {
            text = content
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(tv)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton(R.string.share_image) { _, _ ->
                startActivity(
                    android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, content)
                        },
                        getString(R.string.share_image)
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 页码=最后成功加载的页；文学标「本地目录页」，贴图标「站点页」；总页数未知禁用跳页。 */
    private fun renderPageLabel() {
        val scope = getString(if (isText) R.string.page_scope_lit else R.string.page_scope_img)
        bind.tvPage.text = if (vm.totalKnown) {
            getString(R.string.page_label_scoped, vm.page, scope, vm.totalPages)
        } else {
            getString(R.string.page_label_unknown, vm.page, scope)
        }
    }

    /** 长按收藏：状态翻转 + 轻提示（可撤销）+ 成功节奏触感。 */
    private fun onLongPress(t: ThreadEntity) {
        val adding = !t.favorite
        Haptics.tick(bind.root)
        if (adding) Haptics.confirm(bind.root)
        vm.setFavorite(t, adding) { snap, v ->
            undoSnack(t.tid, snap, v, getString(if (adding) R.string.fav_added else R.string.fav_removed))
        }
    }

    /** 左滑标记不喜欢：可见背景（danger 底 + 文案），条目即时隐藏，Snackbar 可撤销。 */
    private fun setupSwipe() {
        val cb = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) = false

            override fun onChildDraw(
                c: Canvas,
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                // 可见背景：左滑露出 danger 底 + 「不喜欢」文字与图标提示
                if (dX < 0) {
                    val view = vh.itemView
                    val bg = ContextCompat.getColor(requireContext(), R.color.danger)
                    val p = android.graphics.Paint().apply { color = bg }
                    c.drawRect(
                        view.right.toFloat() + dX, view.top.toFloat(),
                        view.right.toFloat(), view.bottom.toFloat(), p
                    )
                    val alpha = (255f * (-dX / view.width)).coerceIn(0f, 255f).toInt()
                    val text = getString(R.string.menu_act_dislike)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 14 * resources.displayMetrics.density
                        isAntiAlias = true
                        this.alpha = alpha
                    }
                    val textW = paint.measureText(text)
                    c.drawText(
                        text,
                        view.right - dp(24) - textW,
                        view.centerY() + paint.textSize / 3,
                        paint
                    )
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }

            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
                val t = adapter.itemAt(pos) ?: return
                Haptics.confirm(bind.root)
                vm.setDislike(t, true) { snap, v ->
                    undoSnack(t.tid, snap, v, getString(R.string.dislike_marked))
                }
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(bind.recycler)
    }

    private fun View.centerY(): Float = (top + bottom) / 2f

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(requireContext(), t)

    companion object {
        private const val ARG_SOURCE = "sourceId"
        private const val MENU_VERSION = 1
        private const val MENU_REFRESH = 2
        private const val MENU_CHECK_UPDATE = 3
        private const val MENU_CRASH_LOGS = 4
        private const val MENU_KEYWORDS = 5
        fun newInstance(sourceId: String) = ThreadListFragment().apply {
            arguments = Bundle().apply { putString(ARG_SOURCE, sourceId) }
        }
    }
}
