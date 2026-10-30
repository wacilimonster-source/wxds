package com.example.litreader.ui.fav

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.FragmentFavoritesBinding
import com.example.litreader.ui.list.ThreadAdapter
import com.example.litreader.ui.main.MainActivity
import com.example.litreader.util.ReadProgress
import com.example.litreader.util.Skeleton

/** 书架：最近浏览 + 收藏 两段共用一个列表，段内筛选/排序独立。 */
class FavoritesFragment : Fragment() {
    private lateinit var bind: FragmentFavoritesBinding
    private lateinit var vm: FavoritesViewModel
    private lateinit var adapter: ThreadAdapter

    /** 0=收藏先后 1=帖子时间 2=标题（仅收藏段） */
    private var sortMode = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        bind = FragmentFavoritesBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm = ViewModelProvider(
            this, FavoritesVmFactory((requireActivity().application as App).database)
        )[FavoritesViewModel::class.java]
        adapter = ThreadAdapter({ open(it) }, { vm.toggleFavorite(it) })
        bind.recycler.layoutManager = LinearLayoutManager(requireContext())
        bind.recycler.adapter = adapter

        val prefs = requireActivity().getSharedPreferences("fav", Context.MODE_PRIVATE)
        sortMode = prefs.getInt("sort", 0)
        // 段持久化：0=最近 1=收藏；首次安装默认停在收藏段
        vm.sectionFavorites = prefs.getInt("section", 1) == 1

        bind.sectionGroup.check(if (vm.sectionFavorites) R.id.btnSectionFav else R.id.btnSectionRecent)
        bind.sectionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val favorites = checkedId == R.id.btnSectionFav
            if (favorites != vm.sectionFavorites) {
                prefs.edit().putInt("section", if (favorites) 1 else 0).apply()
                vm.setSection(favorites)
                renderSectionUi()
            }
        }

        bind.filterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            vm.applyFilter(
                when (checkedId) {
                    R.id.btnFilterLit -> MainActivity.SECTION_LIT
                    R.id.btnFilterImg -> MainActivity.SECTION_IMG
                    else -> null
                }
            )
        }

        bind.tvFavSort.setOnClickListener { showSortMenu() }
        bind.tvFavClear.setOnClickListener { confirmClearRecents() }
        renderSectionUi()

        vm.threads.observe(viewLifecycleOwner) { list ->
            adapter.submit(
                if (vm.sectionFavorites) sort(list) else list,
                ReadProgress.map(requireActivity())
            )
            bind.emptyBox.visibility = if (list.isEmpty() && vm.loading.value != true) View.VISIBLE else View.GONE
        }
        vm.loading.observe(viewLifecycleOwner) { loading ->
            if (loading && adapter.itemCount == 0) {
                Skeleton.show(bind.skeleton)
                bind.emptyBox.visibility = View.GONE
            } else {
                Skeleton.hide(bind.skeleton)
                if (!loading) {
                    bind.emptyBox.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** 段切换后的按钮与空态文案（列表数据由 vm 负责）。 */
    private fun renderSectionUi() {
        val favorites = vm.sectionFavorites
        bind.tvFavSort.visibility = if (favorites) View.VISIBLE else View.GONE
        bind.tvFavClear.visibility = if (favorites) View.GONE else View.VISIBLE
        bind.tvEmptyTitle.setText(
            if (favorites) R.string.empty_fav_title else R.string.empty_recent_title
        )
        bind.tvEmptyHint.setText(
            if (favorites) R.string.empty_fav_hint else R.string.empty_recent_hint
        )
        adapter.highlightFirst = !favorites
    }

    private fun confirmClearRecents() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_recent)
            .setMessage(R.string.clear_recent_confirm)
            .setPositiveButton(R.string.clear_recent) { _, _ -> vm.clearRecents() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 排序菜单：收藏先后 / 帖子时间 / 标题，选择持久化（仅收藏段）。 */
    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), bind.tvFavSort)
        val labels = listOf(
            R.string.fav_sort_fav,
            R.string.fav_sort_time,
            R.string.fav_sort_title
        )
        labels.forEachIndexed { i, res ->
            popup.menu.add(0, i, i, getString(res)).isChecked = sortMode == i
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener {
            if (sortMode != it.itemId) {
                sortMode = it.itemId
                requireActivity().getSharedPreferences("fav", Context.MODE_PRIVATE)
                    .edit().putInt("sort", sortMode).apply()
                vm.load()
            }
            true
        }
        popup.show()
    }

    private fun sort(list: List<ThreadEntity>): List<ThreadEntity> = when (sortMode) {
        1 -> list.sortedByDescending { it.timestamp }
        2 -> list.sortedBy { it.title }
        else -> list.sortedWith(
            compareByDescending<ThreadEntity> { it.favoriteAt }.thenByDescending { it.timestamp }
        )
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.load()
    }

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(requireContext(), t)
}
