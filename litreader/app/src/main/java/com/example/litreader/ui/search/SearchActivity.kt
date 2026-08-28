package com.example.litreader.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.ActivitySearchBinding
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.ui.list.ThreadAdapter

class SearchActivity : AppCompatActivity() {
    private lateinit var bind: ActivitySearchBinding
    private lateinit var vm: SearchViewModel
    private lateinit var adapter: ThreadAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(bind.root)
        title = "搜索"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        vm = ViewModelProvider(this, SearchVmFactory((application as App).database))[SearchViewModel::class.java]
        adapter = ThreadAdapter({ open(it) }, { vm.toggleFavorite(it) }, showSection = true)
        bind.recycler.layoutManager = LinearLayoutManager(this)
        bind.recycler.adapter = adapter

        bind.btnClear.setOnClickListener {
            bind.etSearch.setText("")
            bind.etSearch.requestFocus()
        }

        bind.etSearch.setOnEditorActionListener { _, _, _ ->
            val q = bind.etSearch.text.toString().trim()
            if (q.isNotEmpty()) vm.search(q) else Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            hideKeyboard()
            true
        }

        vm.threads.observe(this) { list ->
            adapter.submit(list)
            bind.emptyBox.visibility = if (list.isEmpty() && vm.query.isNotEmpty()) View.VISIBLE else View.GONE
            if (list.isEmpty() && vm.query.isNotEmpty()) {
                bind.tvEmptyTitle.text = "未找到「${vm.query}」相关内容"
                bind.tvEmptyHint.text = "换个关键词试试"
            }
        }
        vm.loading.observe(this) { bind.progress.visibility = if (it) View.VISIBLE else View.GONE }

        bind.etSearch.requestFocus()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun open(t: ThreadEntity) {
        startActivity(Intent(this, ThreadDetailActivity::class.java).apply {
            putExtra("tid", t.tid)
            putExtra("title", t.title)
            putExtra("favorite", t.favorite)
            putExtra("sourceId", t.sourceId)
        })
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(bind.etSearch.windowToken, 0)
    }
}
