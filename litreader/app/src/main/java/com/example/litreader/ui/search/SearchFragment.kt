package com.example.litreader.ui.search

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.FragmentSearchBinding
import com.example.litreader.ui.list.ThreadAdapter
import com.example.litreader.ui.main.MainActivity
import com.example.litreader.util.Skeleton

/** 搜索页签：常驻内存 + prefs 落盘，切走页签或重启后上次查询与结果保留。 */
class SearchFragment : Fragment() {
    private lateinit var bind: FragmentSearchBinding
    private lateinit var vm: SearchViewModel
    private lateinit var adapter: ThreadAdapter

    /** 0=最新 1=最多点赞 2=最多回复 */
    private var sortMode = 0

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
        adapter = ThreadAdapter({ open(it) }, { vm.toggleFavorite(it) }, showSection = true)
        bind.recycler.layoutManager = LinearLayoutManager(requireContext())
        bind.recycler.adapter = adapter

        // 恢复落盘的排序与区筛选（页面重建/App 重启后不丢）
        sortMode = prefs.getInt("sort", 0)
        vm.sortMode = sortMode
        vm.filter = prefs.getString("zone", "")?.takeIf { it.isNotEmpty() }
        bind.zoneGroup.check(
            when (vm.filter) {
                MainActivity.SECTION_LIT -> R.id.btnZoneLit
                MainActivity.SECTION_IMG -> R.id.btnZoneImg
                else -> R.id.btnZoneAll
            }
        )

        bind.btnClear.setOnClickListener { bind.etSearch.setText("") }

        bind.etSearch.setOnEditorActionListener { _, _, _ ->
            val q = bind.etSearch.text.toString().trim()
            if (q.isNotEmpty()) {
                bind.filterBar.visibility = View.VISIBLE
                vm.search(q)
            } else {
                Toast.makeText(requireContext(), "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            }
            hideKeyboard()
            true
        }

        bind.zoneGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            vm.applyFilter(
                when (checkedId) {
                    R.id.btnZoneLit -> MainActivity.SECTION_LIT
                    R.id.btnZoneImg -> MainActivity.SECTION_IMG
                    else -> null
                }
            )
        }
        bind.tvSearchSort.setOnClickListener { showSortMenu() }

        vm.threads.observe(viewLifecycleOwner, ::render)
        vm.loading.observe(viewLifecycleOwner) { loading ->
            if (loading && adapter.itemCount == 0) {
                Skeleton.show(bind.skeleton)
                bind.emptyBox.visibility = View.GONE
            } else {
                Skeleton.hide(bind.skeleton)
                if (!loading) render(vm.threads.value ?: emptyList())
            }
        }

        // 上次搜索在进入页签时恢复（本地库重查，毫秒级）；没有则显示引导空态。
        // 全选恢复词：用户直接输入即覆盖旧词，不会拼成"旧词+新词"
        val last = vm.lastQuery()
        if (last.isNotEmpty()) {
            bind.etSearch.setText(last)
            bind.etSearch.setSelection(0, last.length)
            bind.filterBar.visibility = View.VISIBLE
            vm.search(last)
        } else {
            render(emptyList())
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

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(requireContext(), t)

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(bind.etSearch.windowToken, 0)
    }
}
