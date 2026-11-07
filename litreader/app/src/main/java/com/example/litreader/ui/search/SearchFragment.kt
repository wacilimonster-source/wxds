package com.example.litreader.ui.search

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.FragmentSearchBinding
import com.example.litreader.ui.list.ThreadAdapter
import com.example.litreader.util.Haptics
import com.example.litreader.util.Skeleton
import com.example.litreader.util.UiTips

/** 搜索页签：常驻内存 + prefs 落盘；清空回初态，状态/错误/零结果分离。 */
class SearchFragment : Fragment() {
    private lateinit var bind: FragmentSearchBinding
    private lateinit var vm: SearchViewModel
    private lateinit var adapter: ThreadAdapter

    private lateinit var fieldOff: Drawable
    private lateinit var fieldOn: Drawable

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        bind = FragmentSearchBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val prefs = requireActivity().getSharedPreferences("search", Context.MODE_PRIVATE)
        vm = ViewModelProvider(
            this,
            SearchVmFactory((requireActivity().application as App).database, prefs)
        )[SearchViewModel::class.java]
        adapter = ThreadAdapter(
            { open(it) }, { onLongPress(it) },
            onFavorite = { t ->
                Haptics.tick(bind.root)
                val target = !t.favorite
                vm.toggleFavorite(t)
                UiTips.snack(
                    bind.root,
                    getString(if (target) R.string.fav_added else R.string.fav_removed),
                    getString(R.string.undo),
                    onAction = { vm.toggleFavorite(t.copy(favorite = target)) }
                )
            },
            onDislike = { onDislikeToggle(it) },
            showSection = true
        )
        bind.recycler.layoutManager = LinearLayoutManager(requireContext())
        bind.recycler.adapter = adapter

        fieldOff = ContextCompat.getDrawable(requireContext(), R.drawable.bg_search_field_off)!!
        fieldOn = ContextCompat.getDrawable(requireContext(), R.drawable.bg_search_field_on)!!

        // 恢复落盘的排序与区筛选（页面重建/App 重启后不丢）
        vm.sortMode = prefs.getInt("sort", 0)
        vm.filter = prefs.getString("zone", "")?.takeIf { it.isNotEmpty() }
        renderSortLabel()

        // 清除 = 完整初态回滚：输入/已提交词/结果/last_query 同步清空，历史保留
        bind.btnClear.setOnClickListener {
            bind.etSearch.setText("")
            vm.clearQuery()
            bind.filterBar.visibility = View.GONE
            bind.tvSearchScope.visibility = View.GONE
            bind.historyBar.visibility = View.GONE
            render(emptyList())
            renderHistory()
            bind.etSearch.requestFocus()
        }

        // 胶囊搜索框聚焦态：描边转朱砂
        bind.etSearch.setOnFocusChangeListener { _, hasFocus ->
            bind.searchField.background = if (hasFocus) fieldOn else fieldOff
        }

        bind.etSearch.setOnEditorActionListener { _, _, _ ->
            val q = bind.etSearch.text.toString().trim()
            if (q.isNotEmpty()) {
                showQueryUi()
                vm.search(q)
            } else {
                bind.btnClear.performClick()
            }
            hideKeyboard()
            true
        }

        bind.tvSearchSort.setOnClickListener { showSortMenu() }
        bind.btnSearchRetry.setOnClickListener { vm.retry() }

        vm.threads.observe(viewLifecycleOwner) { render(it) }
        vm.loading.observe(viewLifecycleOwner) { loading ->
            // 竞态修复：DiffUtil 后台算，loading=false 时 itemCount 未必更新
            val empty = vm.threads.value.isNullOrEmpty()
            if (loading && empty && vm.query.isNotEmpty()) {
                Skeleton.show(bind.skeleton.root)
                bind.emptyBox.visibility = View.GONE
            } else {
                Skeleton.hide(bind.skeleton.root)
                if (!loading) render(vm.threads.value ?: emptyList())
            }
            renderScope()
        }
        vm.error.observe(viewLifecycleOwner) { render(vm.threads.value ?: emptyList()) }
        vm.opFailure.observe(viewLifecycleOwner) { failed ->
            if (failed) {
                UiTips.snack(bind.root, getString(R.string.op_failed), anchor = bind.searchField)
                vm.consumeOpFailure()
            }
        }

        // 上次搜索在进入页签时恢复（本地库重查，毫秒级）；没有则显示历史/引导空态。
        // 全选恢复词：用户直接输入即覆盖旧词，不会拼成"旧词+新词"
        val last = vm.lastQuery()
        if (last.isNotEmpty()) {
            bind.etSearch.setText(last)
            bind.etSearch.setSelection(0, last.length)
            showQueryUi()
            vm.search(last)
        } else {
            render(emptyList())
            renderHistory()
        }
    }

    private fun showQueryUi() {
        bind.filterBar.visibility = View.VISIBLE
        renderZoneChips()
        renderSortLabel()
        bind.historyBar.visibility = View.GONE
    }

    /** 底部页签进入恢复查询不弹键盘；列表入口 presetSource+focus 弹键盘。 */
    fun presetSource(sourceId: String) {
        if (vm.filter != sourceId) {
            vm.applyFilter(sourceId)
            if (::bind.isInitialized && bind.filterBar.visibility == View.VISIBLE) renderZoneChips()
        }
    }

    /** 区筛选 chips：全部 / 文学 / 贴图（统一胶囊规格，选择持久化）。 */
    private fun renderZoneChips() {
        val row = bind.zoneRow
        row.removeAllViews()
        val options = listOf(
            null to getString(R.string.filter_all),
            com.example.litreader.ui.main.MainActivity.SECTION_LIT to getString(R.string.tab_lit),
            com.example.litreader.ui.main.MainActivity.SECTION_IMG to getString(R.string.tab_img)
        )
        options.forEach { (value, label) ->
            val tv = TextView(requireContext(), null, 0, R.style.LitChip).apply {
                text = label
                isSelected = vm.filter == value
                minHeight = dp(48)
                gravity = Gravity.CENTER
                setOnClickListener {
                    Haptics.tick(this)
                    if (vm.filter != value) {
                        vm.applyFilter(value)  // VM 内部持久化 zone
                        renderZoneChips()
                    }
                }
            }
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            row.addView(tv)
        }
    }

    /** 历史 chips：可见删除入口（✕）+ 长按等价；点击词即搜。 */
    private fun renderHistory() {
        val items = vm.history()
        bind.historyBar.visibility =
            if (bind.etSearch.text.isNullOrBlank() && items.isNotEmpty()) View.VISIBLE else View.GONE
        val row = bind.historyRow
        row.removeAllViews()
        items.forEach { kw ->
            val item = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            }
            val chip = TextView(requireContext(), null, 0, R.style.LitChip).apply {
                text = kw
                setOnClickListener {
                    Haptics.tick(this)
                    bind.etSearch.setText(kw)
                    bind.etSearch.setSelection(kw.length)
                    showQueryUi()
                    vm.search(kw)
                    hideKeyboard()
                }
            }
            val del = TextView(requireContext()).apply {
                text = "✕"
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.inkSecondary))
                contentDescription = getString(R.string.history_delete_cd)
                minHeight = dp(48)
                minWidth = dp(32)
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_filter_row)
                setOnClickListener {
                    Haptics.key(this)
                    vm.removeHistory(kw)
                    renderHistory()
                }
            }
            item.addView(chip)
            item.addView(del, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = -(4 * resources.displayMetrics.density).toInt() })
            row.addView(item)
        }
    }

    /** 渲染：初态 / 零结果 / 错误（互相独立，错误可重试且保留查询）。 */
    private fun render(list: List<ThreadEntity>) {
        adapter.submit(list)
        val failed = vm.error.value == true
        val empty = list.isEmpty()
        bind.emptyBox.visibility = if ((empty || failed) && vm.loading.value != true) View.VISIBLE else View.GONE
        bind.btnSearchRetry.visibility = if (failed) View.VISIBLE else View.GONE
        when {
            failed -> {
                bind.tvEmptyTitle.text = getString(R.string.search_error_title)
                bind.tvEmptyHint.text = getString(R.string.search_error_hint)
            }
            vm.query.isEmpty() -> {
                bind.tvEmptyTitle.text = getString(R.string.search_initial_title)
                bind.tvEmptyHint.text = getString(R.string.search_initial_hint)
            }
            else -> {
                bind.tvEmptyTitle.text = getString(R.string.search_empty_title, vm.query)
                bind.tvEmptyHint.text = getString(R.string.search_empty_hint)
            }
        }
        renderScope()
        renderHistory()
    }

    /** 范围说明/结果数：明示只搜本地已缓存标题。 */
    private fun renderScope() {
        val hasQuery = vm.query.isNotEmpty()
        bind.tvSearchScope.visibility = if (hasQuery) View.VISIBLE else View.GONE
        if (hasQuery) {
            bind.tvSearchScope.text = getString(
                R.string.search_scope_n, vm.threads.value?.size ?: 0
            )
        }
    }

    /** 排序按钮显示当前值。 */
    private fun renderSortLabel() {
        val label = when (vm.sortMode) {
            1 -> R.string.search_sort_likes
            2 -> R.string.search_sort_replies
            else -> R.string.search_sort_new
        }
        bind.tvSearchSort.text = getString(R.string.search_sort_current, getString(label))
    }

    /** 不喜欢按钮：状态翻转 + 可撤销（搜索结果不隐藏，仅写状态）。 */
    private fun onDislikeToggle(t: ThreadEntity) {
        val target = !t.dislike
        if (target) Haptics.confirm(bind.root) else Haptics.tick(bind.root)
        vm.setDislike(t, target) { snap, v ->
            UiTips.snack(
                bind.root,
                getString(if (target) R.string.dislike_marked else R.string.dislike_removed),
                getString(R.string.undo),
                onAction = { vm.undoMark(t.tid, snap, v) }
            )
        }
    }

    /** 列表页放大镜入口切过来时聚焦输入框并弹键盘。 */
    fun focusInput() {
        bind.etSearch.requestFocus()
        bind.etSearch.postDelayed({
            if (!isAdded) return@postDelayed
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(bind.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    /** 排序菜单：最新 / 最多点赞 / 最多回复，选择持久化并显示当前值。 */
    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), bind.tvSearchSort)
        val labels = listOf(R.string.search_sort_new, R.string.search_sort_likes, R.string.search_sort_replies)
        labels.forEachIndexed { i, res ->
            popup.menu.add(0, i, i, getString(res)).isChecked = vm.sortMode == i
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener {
            if (vm.sortMode != it.itemId) {
                vm.applySort(it.itemId)
                renderSortLabel()
            }
            true
        }
        popup.show()
    }

    /** 长按收藏：状态翻转 + 轻提示（可撤销）。 */
    private fun onLongPress(t: ThreadEntity) {
        val adding = !t.favorite
        Haptics.tick(bind.root)
        if (adding) Haptics.confirm(bind.root)
        vm.toggleFavorite(t)
        UiTips.snack(
            bind.root,
            getString(if (adding) R.string.fav_added else R.string.fav_removed),
            getString(R.string.undo),
            onAction = { vm.toggleFavorite(t.copy(favorite = adding)) }
        )
    }

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(requireContext(), t)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(bind.etSearch.windowToken, 0)
    }
}
