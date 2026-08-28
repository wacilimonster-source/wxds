package com.example.litreader.ui.fav

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        vm.threads.observe(viewLifecycleOwner) { list ->
            adapter.submit(list)
            bind.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        vm.loading.observe(viewLifecycleOwner) {
            bind.progress.visibility = if (it) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.load()
    }

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(requireContext(), t)
}
