package com.example.litreader.ui.list

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import com.example.litreader.ui.search.SearchActivity
import com.example.litreader.util.UpdateManager

/** 文学/贴图共用的列表页，按 sourceId 区分数据源。 */
class ThreadListFragment : Fragment() {
    private lateinit var bind: FragmentListBinding
    private lateinit var vm: ThreadListViewModel
    private lateinit var adapter: ThreadAdapter
    private var sourceId: String = ""
    private var syncVm: CatalogSyncViewModel? = null
    private var lastObservedRunning: Boolean? = null

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
            this, ThreadListVmFactory((requireActivity().application as App).database, source.id)
        )[ThreadListViewModel::class.java]
        adapter = ThreadAdapter({ open(it) }, { vm.toggleFavorite(it) })
        bind.recycler.layoutManager = LinearLayoutManager(requireContext())
        bind.recycler.adapter = adapter

        val isText = source.style == SourceStyle.TEXT
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
        bind.btnPrev.setOnClickListener { vm.prevPage() }
        bind.btnNext.setOnClickListener { vm.nextPage() }
        bind.tvPage.setOnClickListener { showJumpDialog() }
        bind.tvPage.contentDescription = getString(R.string.jump_page)
        bind.btnSearch.setOnClickListener { startActivity(Intent(requireContext(), SearchActivity::class.java)) }
        bind.btnMore.setOnClickListener { showMoreMenu(view) }

        vm.threads.observe(viewLifecycleOwner) { list ->
            adapter.submit(list)
            bind.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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
            if (!loading) bind.swipe.isRefreshing = false
            bind.progress.visibility = if (loading && !bind.swipe.isRefreshing) View.VISIBLE else View.GONE
            bind.btnPrev.isEnabled = !loading && vm.page > 1
            bind.btnNext.isEnabled = !loading
            renderPageLabel()
        }
        vm.error.observe(viewLifecycleOwner) { msg ->
            if (msg != null) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        vm.load(1)
    }

    private var firstResume = true

    override fun onResume() {
        super.onResume()
        // 从阅读器/详情返回时刷新已读标记（本地读取，开销极小）；首次进入不重复加载
        if (firstResume) {
            firstResume = false
            return
        }
        if (this::vm.isInitialized) vm.load(vm.page)
    }

    /** 手动刷新：文学区走目录增量同步（完成回调里会重载列表），贴图区沿用在线补页。 */
    private fun onManualRefresh() {
        val sync = syncVm
        if (sync != null) {
            vm.load(vm.page)
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
                vm.load(vm.page)
            } else if (lastObservedRunning == null && st.error == null) {
                // 进入页面时同步已完成（粘性状态）→ 直接读一次库
                vm.load(vm.page)
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
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(getString(R.string.check_update))
        popup.setOnMenuItemClickListener {
            UpdateManager.checkAndPromptUpdate(requireActivity(), lifecycleScope, silent = false)
            true
        }
        popup.show()
    }

    private fun renderPageLabel() {
        bind.tvPage.text = "${vm.page} / ${vm.totalPages}"
    }

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(requireContext(), t)

    companion object {
        private const val ARG_SOURCE = "sourceId"
        fun newInstance(sourceId: String) = ThreadListFragment().apply {
            arguments = Bundle().apply { putString(ARG_SOURCE, sourceId) }
        }
    }
}
