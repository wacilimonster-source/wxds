package com.example.litreader.ui.fav

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.FragmentFavoritesBinding
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.ui.list.ThreadAdapter
import com.example.litreader.ui.main.MainActivity

class FavoritesFragment : Fragment() {
    private lateinit var bind: FragmentFavoritesBinding
    private lateinit var vm: FavoritesViewModel
    private lateinit var adapter: ThreadAdapter

    /** 0=收藏先后 1=帖子时间 2=标题 */
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

        sortMode = requireActivity()
            .getSharedPreferences("fav", Context.MODE_PRIVATE).getInt("sort", 0)

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

        vm.threads.observe(viewLifecycleOwner) { list ->
            adapter.submit(sort(list))
            bind.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        vm.loading.observe(viewLifecycleOwner) {
            bind.progress.visibility = if (it) View.VISIBLE else View.GONE
        }
    }

    /** 排序菜单：收藏先后 / 帖子时间 / 标题，选择持久化。 */
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
