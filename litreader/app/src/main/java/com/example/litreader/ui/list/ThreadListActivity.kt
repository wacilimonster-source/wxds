package com.example.litreader.ui.list

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.databinding.ActivityListBinding
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.util.UpdateManager

class ThreadListActivity : AppCompatActivity() {
    private lateinit var bind: ActivityListBinding
    private lateinit var vm: ThreadListViewModel
    private lateinit var adapter: ThreadAdapter
    private val catButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityListBinding.inflate(layoutInflater)
        setContentView(bind.root)

        vm = ViewModelProvider(this, ThreadListVmFactory((application as App).database))[ThreadListViewModel::class.java]
        adapter = ThreadAdapter({ open(it) }, { vm.toggleFavorite(it) })
        bind.recycler.layoutManager = LinearLayoutManager(this)
        bind.recycler.adapter = adapter

        buildCategoryBar()

        bind.swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent))
        bind.swipe.setOnRefreshListener { vm.refresh() }
        bind.btnPrev.setOnClickListener { vm.prevPage() }
        bind.btnNext.setOnClickListener { vm.nextPage() }
        bind.btnSearch.setOnClickListener {
            val q = bind.etSearch.text.toString()
            if (q.isBlank()) vm.refresh() else vm.search(q)
        }
        bind.btnUpdate.setOnClickListener { UpdateManager.checkAndPromptUpdate(this, lifecycleScope, silent = false) }
        bind.btnFav.setOnClickListener {
            vm.onlyFavorite = !vm.onlyFavorite
            renderFavButton()
            vm.load(1)
        }

        vm.threads.observe(this) { list ->
            adapter.submit(list)
            bind.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            bind.swipe.visibility = if (list.isEmpty() && vm.onlyFavorite) View.GONE else View.VISIBLE
        }
        vm.loading.observe(this) { loading ->
            if (!loading) bind.swipe.isRefreshing = false
            bind.progress.visibility = if (loading) View.VISIBLE else View.GONE
            bind.btnPrev.isEnabled = !loading && vm.page > 1
            bind.btnNext.isEnabled = !loading
        }
        vm.error.observe(this) { msg ->
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        renderFavButton()
        vm.load(1)
        UpdateManager.checkAndPromptUpdate(this, lifecycleScope, silent = true)
    }

    private fun buildCategoryBar() {
        val cats = SourceRegistry.get("t66y")?.categories ?: return
        bind.catBar.removeAllViews()
        catButtons.clear()
        cats.forEach { (value, label) ->
            val b = Button(this)
            b.text = label
            b.setAllCaps(false)
            b.textSize = 13f
            b.isClickable = true
            b.setPadding(24, 8, 24, 8)
            b.minimumWidth = 0
            b.minimumHeight = 0
            b.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
            b.setOnClickListener {
                vm.category = value
                vm.load(1)
                renderCatSelection()
            }
            bind.catBar.addView(b)
            catButtons.add(b)
        }
        renderCatSelection()
    }

    private fun renderCatSelection() {
        val cats = SourceRegistry.get("t66y")?.categories ?: return
        cats.forEachIndexed { i, pair ->
            val selected = pair.first == vm.category
            val b = catButtons.getOrNull(i) ?: return@forEachIndexed
            b.setBackgroundResource(
                if (selected) R.drawable.bg_cat_selected else R.drawable.bg_cat_normal
            )
            b.setTextColor(
                ContextCompat.getColor(
                    this, if (selected) R.color.catTextSelected else R.color.catTextNormal
                )
            )
        }
    }

    private fun renderFavButton() {
        bind.btnFav.setImageResource(
            if (vm.onlyFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )
    }

    private fun open(t: ThreadEntity) {
        startActivity(Intent(this, ThreadDetailActivity::class.java).apply {
            putExtra("tid", t.tid)
            putExtra("title", t.title)
            putExtra("favorite", t.favorite)
        })
    }
}
