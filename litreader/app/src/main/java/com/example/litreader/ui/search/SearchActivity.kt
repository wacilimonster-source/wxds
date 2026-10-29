package com.example.litreader.ui.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.ActivitySearchBinding
import com.example.litreader.ui.list.ThreadAdapter
import com.example.litreader.ui.main.MainActivity

class SearchActivity : AppCompatActivity() {
    private lateinit var bind: ActivitySearchBinding
    private lateinit var vm: SearchViewModel
    private lateinit var adapter: ThreadAdapter

    /** 0=最新 1=最多点赞 2=最多回复 */
    private var sortMode = 0

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

        sortMode = getSharedPreferences("search", Context.MODE_PRIVATE).getInt("sort", 0)
        vm.sortMode = sortMode

        bind.btnClear.setOnClickListener {
            bind.etSearch.setText("")
            bind.etSearch.requestFocus()
        }

        bind.etSearch.setOnEditorActionListener { _, _, _ ->
            val q = bind.etSearch.text.toString().trim()
            if (q.isNotEmpty()) {
                bind.filterBar.visibility = View.VISIBLE
                vm.search(q)
            } else {
                Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show()
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

    /** 排序菜单：最新 / 最多点赞 / 最多回复，选择持久化。 */
    private fun showSortMenu() {
        val popup = PopupMenu(this, bind.tvSearchSort)
        val labels = listOf(R.string.search_sort_new, R.string.search_sort_likes, R.string.search_sort_replies)
        labels.forEachIndexed { i, res ->
            popup.menu.add(0, i, i, getString(res)).isChecked = sortMode == i
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener {
            if (sortMode != it.itemId) {
                sortMode = it.itemId
                getSharedPreferences("search", Context.MODE_PRIVATE).edit().putInt("sort", sortMode).apply()
                vm.applySort(sortMode)
            }
            true
        }
        popup.show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(this, t)

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(bind.etSearch.windowToken, 0)
    }
}
