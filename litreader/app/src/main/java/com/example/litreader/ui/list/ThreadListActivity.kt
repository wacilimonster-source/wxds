package com.example.litreader.ui.list

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.databinding.ActivityListBinding
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.ui.fav.FavoritesActivity
import com.example.litreader.ui.search.SearchActivity
import com.example.litreader.util.UpdateManager

class ThreadListActivity : AppCompatActivity() {
    private lateinit var bind: ActivityListBinding
    private lateinit var vm: ThreadListViewModel
    private lateinit var adapter: ThreadAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityListBinding.inflate(layoutInflater)
        setContentView(bind.root)

        vm = ViewModelProvider(this, ThreadListVmFactory((application as App).database))[ThreadListViewModel::class.java]
        adapter = ThreadAdapter({ open(it) }, { vm.toggleFavorite(it) })
        bind.recycler.layoutManager = LinearLayoutManager(this)
        bind.recycler.adapter = adapter

        bind.swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent))
        bind.swipe.setOnRefreshListener { vm.refresh() }
        bind.btnPrev.setOnClickListener { vm.prevPage() }
        bind.btnNext.setOnClickListener { vm.nextPage() }
        bind.btnReload.setOnClickListener { vm.refresh() }
        bind.btnUpdate.setOnClickListener { UpdateManager.checkAndPromptUpdate(this, lifecycleScope, silent = false) }
        bind.btnFav.setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
        bind.btnSearch.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }

        vm.threads.observe(this) { list ->
            adapter.submit(list)
            bind.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            bind.swipe.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            renderPageLabel()
        }
        vm.loading.observe(this) { loading ->
            if (!loading) bind.swipe.isRefreshing = false
            bind.progress.visibility = if (loading && !bind.swipe.isRefreshing) View.VISIBLE else View.GONE
            bind.btnPrev.isEnabled = !loading && vm.page > 1
            bind.btnNext.isEnabled = !loading
            renderPageLabel()
        }
        vm.error.observe(this) { msg ->
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        vm.load(1)
        UpdateManager.checkAndPromptUpdate(this, lifecycleScope, silent = true)
    }

    private fun renderPageLabel() {
        bind.tvPage.text = "${vm.page} / ${vm.totalPages}"
    }

    private fun open(t: ThreadEntity) {
        startActivity(Intent(this, ThreadDetailActivity::class.java).apply {
            putExtra("tid", t.tid)
            putExtra("title", t.title)
            putExtra("favorite", t.favorite)
        })
    }
}
