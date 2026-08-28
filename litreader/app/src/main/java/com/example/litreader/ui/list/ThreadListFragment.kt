package com.example.litreader.ui.list

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
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

        bind.swipe.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.accent))
        bind.swipe.setOnRefreshListener { vm.refresh() }
        bind.btnPrev.setOnClickListener { vm.prevPage() }
        bind.btnNext.setOnClickListener { vm.nextPage() }
        bind.btnReload.setOnClickListener { vm.refresh() }
        bind.btnSearch.setOnClickListener { startActivity(Intent(requireContext(), SearchActivity::class.java)) }
        bind.btnMore.setOnClickListener { showMoreMenu(view) }

        vm.threads.observe(viewLifecycleOwner) { list ->
            adapter.submit(list)
            bind.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            bind.swipe.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            if (list.isEmpty()) {
                bind.tvEmptyTitle.setText(
                    if (source.style == SourceStyle.IMAGE) R.string.empty_gallery_title else R.string.empty_title
                )
                bind.tvEmptyHint.setText(
                    if (source.style == SourceStyle.IMAGE) R.string.empty_gallery_hint else R.string.empty_hint
                )
            }
            renderPageLabel()
        }
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

    private fun open(t: ThreadEntity) {
        startActivity(Intent(requireContext(), ThreadDetailActivity::class.java).apply {
            putExtra("tid", t.tid)
            putExtra("title", t.title)
            putExtra("favorite", t.favorite)
            putExtra("sourceId", t.sourceId)
        })
    }

    companion object {
        private const val ARG_SOURCE = "sourceId"
        fun newInstance(sourceId: String) = ThreadListFragment().apply {
            arguments = Bundle().apply { putString(ARG_SOURCE, sourceId) }
        }
    }
}
