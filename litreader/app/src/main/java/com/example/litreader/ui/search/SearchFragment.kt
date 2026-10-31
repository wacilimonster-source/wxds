package com.example.litreader.ui.search

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
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
import com.example.litreader.ui.main.MainActivity
import com.example.litreader.util.Haptics
import com.example.litreader.util.Skeleton
import com.example.litreader.util.UiTips

/** 搜索页签：常驻内存 + prefs 落盘，切走页签或重启后上次查询与结果保留。 */
class SearchFragment : Fragment() {
    private lateinit var bind: FragmentSearchBinding
    private lateinit var vm: SearchViewModel
    private lateinit var adapter: ThreadAdapter

    /** 0=最新 1=最多点赞 2=最多回复 */
    private var sortMode = 0

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
        adapter = ThreadAdapter({ open(it) }, { onLongPress(it) }, showSection = true)
        bind.recycler.layoutManager = LinearLayoutManager(requireContext())
        bind.recycler.adapter = adapter

        fieldOff = ContextCompat.getDrawable(requireContext(), R.drawable.bg_search_field_off)!!
        fieldOn = ContextCompat.getDrawable(requireContext(), R.drawable.bg_search_field_on)!!

        // 恢复落盘的排序与区筛选（页面重建/App 重启后不丢）
        sortMode = prefs.getInt("sort", 0)
        vm.sortMode = sortMode
        vm.filter = prefs.getString("zone", "")?.takeIf { it.isNotEmpty() }

        bind.btnClear.setOnClickListener {
            bind.etSearch.setText("")
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
                bind.filterBar.visibility = View.VISIBLE
                renderZoneChips()
                vm.search(q)
                bind.historyBar.visibility = View.GONE
            } else {
                UiTips.snack(bind.root, "请输入搜索关键词", anchor = bind.searchField)
            }
            hideKeyboard()
            true
        }

        bind.tvSearchSort.setOnClickListener { showSortMenu() }

        vm.threads.observe(viewLifecycleOwner, ::render)
        vm.loading.observe(viewLifecycleOwner) { loading ->
            // 竞态修复：DiffUtil 后台算，loading=false 时 itemCount 未必更新
            val empty = vm.threads.value.isNullOrEmpty()
            if (loading && empty) {
                Skeleton.show(bind.skeleton.root)
                bind.emptyBox.visibility = View.GONE
            } else {
                Skeleton.hide(bind.skeleton.root)
                if (!loading) render(vm.threads.value ?: emptyList())
            }
        }

        // 上次搜索在进入页签时恢复（本地库重查，毫秒级）；没有则显示历史/引导空态。
        // 全选恢复词：用户直接输入即覆盖旧词，不会拼成"旧词+新词"
        val last = vm.lastQuery()
        if (last.isNotEmpty()) {
            bind.etSearch.setText(last)
            bind.etSearch.setSelection(0, last.length)
            bind.filterBar.visibility = View.VISIBLE
            renderZoneChips()
            vm.search(last)
        } else {
            render(emptyList())
            renderHistory()
        }
    }

    /** 区筛选 chips：全部 / 文学 / 贴图（统一胶囊规格，选择持久化）。 */
    private fun renderZoneChips() {
        val row = bind.zoneRow
        row.removeAllViews()
        val options = listOf(
            null to getString(R.string.filter_all),
            MainActivity.SECTION_LIT to getString(R.string.tab_lit),
            MainActivity.SECTION_IMG to getString(R.string.tab_img)
        )
        options.forEach { (value, label) ->
            val tv = TextView(requireContext(), null, 0, R.style.LitChip).apply {
                text = label
                isSelected = vm.filter == value
                setOnClickListener {
                    Haptics.tick(this)
                    if (vm.filter != value) {
                        vm.applyFilter(value)  // VM 内部持久化 zone
                        renderZoneChips()
                    }
                }
            }
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            row.addView(tv)
        }
    }

    /** 历史 chips：输入框为空且有记录时露出，点击即搜、长按删除。 */
    private fun renderHistory() {
        val items = vm.history()
        bind.historyBar.visibility =
            if (bind.etSearch.text.isNullOrBlank() && items.isNotEmpty()) View.VISIBLE else View.GONE
        val row = bind.historyRow
        row.removeAllViews()
        items.forEach { kw ->
            val tv = TextView(requireContext(), null, 0, R.style.LitChip).apply {
                text = kw
                setOnClickListener {
                    Haptics.tick(this)
                    bind.etSearch.setText(kw)
                    bind.etSearch.setSelection(kw.length)
                    bind.filterBar.visibility = View.VISIBLE
                    renderZoneChips()
                    bind.historyBar.visibility = View.GONE
                    vm.search(kw)
                    hideKeyboard()
                }
                setOnLongClickListener {
                    Haptics.key(this)
                    vm.removeHistory(kw)
                    renderHistory()
                    true
                }
            }
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            row.addView(tv)
        }
    }

    private fun render(list: List<ThreadEntity>) {
        adapter.submit(list)
        val empty = list.isEmpty()
        bind.emptyBox.visibility = if (empty && vm.loading.value != true) View.VISIBLE else View.GONE
        if (!empty) return
        if (vm.query.isEmpty()) {
            bind.tvEmptyTitle.text = "输入关键词搜索"
            bind.tvEmptyHint.text = "搜索已缓存的帖子标题，可在结果里切文学/贴图"
        } else {
            bind.tvEmptyTitle.text = "未找到「${vm.query}」相关内容"
            bind.tvEmptyHint.text = "换个关键词试试"
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

    /** 排序菜单：最新 / 最多点赞 / 最多回复，选择持久化。 */
    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), bind.tvSearchSort)
        val labels = listOf(R.string.search_sort_new, R.string.search_sort_likes, R.string.search_sort_replies)
        labels.forEachIndexed { i, res ->
            popup.menu.add(0, i, i, getString(res)).isChecked = sortMode == i
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener {
            if (sortMode != it.itemId) {
                sortMode = it.itemId
                vm.applySort(sortMode)
            }
            true
        }
        popup.show()
    }

    /** 长按收藏：状态翻转 + 轻提示（可撤销）+ 成功节奏触感。 */
    private fun onLongPress(t: ThreadEntity) {
        val adding = !t.favorite
        vm.toggleFavorite(t)
        if (adding) Haptics.confirm(bind.root)
        UiTips.snack(
            bind.root,
            getString(if (adding) R.string.fav_added else R.string.fav_removed),
            getString(R.string.undo),
            onAction = { vm.toggleFavorite(t.copy(favorite = adding)) }
        )
    }

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(requireContext(), t)

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(bind.etSearch.windowToken, 0)
    }
}
