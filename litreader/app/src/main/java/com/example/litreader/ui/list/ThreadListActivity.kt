package com.example.litreader.ui.list

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.litreader.App
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.ActivityListBinding
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.util.UpdateManager
import androidx.lifecycle.lifecycleScope

class ThreadListActivity : AppCompatActivity() {
    private lateinit var bind: ActivityListBinding
    private lateinit var vm: ThreadListViewModel
    private lateinit var adapter: ThreadAdapter

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        bind = ActivityListBinding.inflate(layoutInflater)
        setContentView(bind.root)

        vm = ViewModelProvider(this, ThreadListVmFactory((application as App).database))[ThreadListViewModel::class.java]
        adapter = ThreadAdapter { open(it) }
        bind.recycler.adapter = adapter
        bind.recycler.setHasFixedSize(true)

        vm.threads.observe(this) { adapter.submit(it) }
        vm.loading.observe(this) { bind.progress.visibility = if (it) View.VISIBLE else View.GONE }

        bind.btnLoad.setOnClickListener { vm.page++; vm.load(vm.page, vm.category) }
        bind.btnCrawl.setOnClickListener { vm.crawlAll() }
        bind.btnSearch.setOnClickListener {
            val q = bind.etSearch.text.toString()
            if (q.isBlank()) vm.load(1, vm.category) else vm.search(q)
        }
        bind.btnUpdate.setOnClickListener { UpdateManager.checkAndPromptUpdate(this, lifecycleScope, silent = false) }
        vm.load(1)
        UpdateManager.checkAndPromptUpdate(this, lifecycleScope, silent = true)
    }

    private fun open(t: ThreadEntity) {
        startActivity(Intent(this, ThreadDetailActivity::class.java).apply {
            putExtra("tid", t.tid)
            putExtra("title", t.title)
        })
    }
}
