package com.example.litreader.ui.list

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.TagCount
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.SourceStyle
import com.example.litreader.databinding.FragmentListBinding
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.util.Constants
import com.example.litreader.util.ReadProgress
import com.example.litreader.util.Skeleton
import com.example.litreader.util.UpdateManager
import java.io.File

/** 文学/贴图共用的列表页，按 sourceId 区分数据源。 */
class ThreadListFragment : Fragment() {
    private lateinit var bind: FragmentListBinding
    private lateinit var vm: ThreadListViewModel
    private lateinit var adapter: ThreadAdapter
    private var sourceId: String = ""
    private var syncVm: CatalogSyncViewModel? = null
    private var lastObservedRunning: Boolean? = null
    private var isText = false

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
        adapter = ThreadAdapter({ open(it) }, { vm.toggleFavorite(it) })
        bind.recycler.layoutManager = LinearLayoutManager(requireContext())
        bind.recycler.adapter = adapter

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
            renderReadChips()
        } else {
            bind.tvSyncStatus.visibility = View.GONE
            bind.readBar.visibility = View.GONE
        }

        bind.swipe.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.accent))
        bind.swipe.setOnRefreshListener { onManualRefresh() }
        bind.btnRefresh.setOnClickListener { onManualRefresh() }
        bind.tvPage.setOnClickListener { showJumpDialog() }
        bind.tvPage.contentDescription = getString(R.string.jump_page)
        bind.btnSearch.setOnClickListener {
            (requireActivity() as? com.example.litreader.ui.main.MainActivity)?.openSearch(focus = true)
        }
        bind.btnMore.setOnClickListener { showMoreMenu(bind.btnMore) }

        // 无限滚动：距底部 6 项时自动追加下一页
        bind.recycler.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (dy <= 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) vm.loadMore()
            }
        })

        vm.threads.observe(viewLifecycleOwner) { list ->
            adapter.submit(list, readProgressMap())
            bind.emptyBox.visibility = if (list.isEmpty() && vm.loading.value != true) View.VISIBLE else View.GONE
            bind.swipe.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            if (list.isEmpty()) {
                bind.tvEmptyTitle.setText(
                    if (isText) R.string.empty_title else R.string.empty_gallery_title
                )
                bind.tvEmptyHint.setText(
                    if (isText) R.string.empty_hint else R.string.empty_gallery_hint
                )
            }
            renderPageLabel()
        }
        vm.tags.observe(viewLifecycleOwner) { renderTags(it) }
        vm.loading.observe(viewLifecycleOwner) { loading ->
            if (!loading) {
                bind.swipe.isRefreshing = false
                Skeleton.hide(bind.skeleton)
                bind.emptyBox.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            } else if (adapter.itemCount == 0) {
                Skeleton.show(bind.skeleton)
                bind.emptyBox.visibility = View.GONE
            }
            renderPageLabel()
        }
        vm.error.observe(viewLifecycleOwner) { msg ->
            if (msg != null) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
        vm.archivedNotice.observe(viewLifecycleOwner) { valid ->
            if (valid > 0) {
                Toast.makeText(
                    requireContext(), getString(R.string.gallery_archived, valid), Toast.LENGTH_LONG
                ).show()
                vm.clearArchivedNotice()
            }
        }

        vm.load(1)
    }

    private var firstResume = true

    override fun onResume() {
        super.onResume()
        // 从阅读器/详情返回时刷新已读标记（图片页全走缓存，瞬间）；首次进入不重复加载
        if (firstResume) {
            firstResume = false
            return
        }
        if (this::vm.isInitialized) vm.reloadLoadedPages()
    }

    /** tid → 已读比例（0<ratio≤1），供列表卡片显示「读到 N%」。 */
    private fun readProgressMap(): Map<String, Float> = ReadProgress.map(requireActivity())

    /** 手动刷新：文学区走目录增量同步（完成后重载已加载页），贴图区回到第 1 页强制重抓。 */
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
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.sync_failed, st.error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                vm.reloadLoadedPages()
            } else if (lastObservedRunning == null && st.error == null) {
                // 进入页面时同步已完成（粘性状态）→ 直接读一次库
                vm.reloadLoadedPages()
            }
        }
        lastObservedRunning = st.running
    }

    /** 标签筛选 chips：全部 + 目录里实际出现的标签（带数量）。 */
    private fun renderTags(counts: List<TagCount>) {
        bind.tagBar.visibility = if (counts.isEmpty()) View.GONE else View.VISIBLE
        val row = bind.tagRow
        row.removeAllViews()
        addChip(row, getString(R.string.filter_all), vm.tag.isEmpty()) { if (vm.tag.isNotEmpty()) vm.selectTag("") }
        counts.forEach { tc ->
            addChip(row, "${tc.tag} ${tc.n}", vm.tag == tc.tag) { if (vm.tag != tc.tag) vm.selectTag(tc.tag) }
        }
    }

    /** 已读状态筛选：全部 / 未读 / 已读 / 已读完（文学区）。 */
    private fun renderReadChips() {
        val options = listOf(
            -1 to getString(R.string.filter_all),
            0 to getString(R.string.filter_unread),
            1 to getString(R.string.filter_read),
            2 to getString(R.string.filter_finished)
        )
        options.forEach { (value, label) ->
            addChip(bind.readRow, label, vm.readFilter == value) {
                if (vm.readFilter != value) vm.selectReadFilter(value)
            }
        }
    }

    private fun addChip(
        row: LinearLayout,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val tv = TextView(requireContext()).apply {
            text = label
            textSize = 11f
            setPadding(dp(9), dp(4), dp(9), dp(4))
            setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_tag)
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selected) R.color.catTextSelected else R.color.catTextNormal
                )
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
        row.addView(tv)
    }

    /** 跳页：点「X / Y」直接输入目标页。 */
    private fun showJumpDialog() {
        val total = vm.totalPages
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 - $total"
        }
        val wrap = FrameLayout(requireContext()).apply {
            val pad = dp(20)
            setPadding(pad, dp(10), pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.jump_page, total))
            .setView(wrap)
            .setPositiveButton(R.string.jump_go) { _, _ ->
                val n = input.text.toString().toIntOrNull()?.coerceIn(1, total)
                if (n != null) vm.load(n)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.let { w ->
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        input.requestFocus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)
        popup.menu.add(0, MENU_VERSION, 0, getString(R.string.current_version, Constants.VERSION_NAME))
            .isEnabled = false
        popup.menu.add(0, MENU_CHECK_UPDATE, 1, getString(R.string.check_update))
        popup.menu.add(0, MENU_CRASH_LOGS, 2, getString(R.string.crash_logs))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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

    /** 本地崩溃日志列表（App 未捕获异常落盘，最多 5 份）。 */
    private fun showCrashLogs() {
        val files = requireContext().getExternalFilesDir("crash")
            ?.listFiles()?.sortedByDescending { it.name }.orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), R.string.crash_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton(R.string.share_image) { _, _ ->
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, content)
                        },
                        getString(R.string.share_image)
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderPageLabel() {
        bind.tvPage.text = "${vm.page} / ${vm.totalPages}"
    }

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(requireContext(), t)

    companion object {
        private const val ARG_SOURCE = "sourceId"
        private const val MENU_VERSION = 1
        private const val MENU_CHECK_UPDATE = 2
        private const val MENU_CRASH_LOGS = 3
        fun newInstance(sourceId: String) = ThreadListFragment().apply {
            arguments = Bundle().apply { putString(ARG_SOURCE, sourceId) }
        }
    }
}
