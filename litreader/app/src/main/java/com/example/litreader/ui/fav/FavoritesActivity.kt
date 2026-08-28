package com.example.litreader.ui.fav

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.ActivityFavoritesBinding
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.ui.list.ThreadAdapter

class FavoritesActivity : AppCompatActivity() {
    private lateinit var bind: ActivityFavoritesBinding
    private lateinit var vm: FavoritesViewModel
    private lateinit var adapter: ThreadAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(bind.root)
        title = "收藏夹"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        vm = ViewModelProvider(this, FavoritesVmFactory((application as App).database))[FavoritesViewModel::class.java]
        adapter = ThreadAdapter({ open(it) }, { vm.toggleFavorite(it) })
        bind.recycler.layoutManager = LinearLayoutManager(this)
        bind.recycler.adapter = adapter

        vm.threads.observe(this) { list ->
            adapter.submit(list)
            bind.emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        vm.loading.observe(this) { bind.progress.visibility = if (it) View.VISIBLE else View.GONE }

        vm.load()
    }

    override fun onResume() {
        super.onResume()
        vm.load()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun open(t: ThreadEntity) {
        startActivity(Intent(this, ThreadDetailActivity::class.java).apply {
            putExtra("tid", t.tid)
            putExtra("title", t.title)
            putExtra("favorite", t.favorite)
        })
    }
}
