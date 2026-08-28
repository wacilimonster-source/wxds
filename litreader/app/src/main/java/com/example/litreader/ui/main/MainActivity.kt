package com.example.litreader.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.databinding.ActivityMainBinding
import com.example.litreader.ui.fav.FavoritesFragment
import com.example.litreader.ui.list.CatalogSyncViewModel
import com.example.litreader.ui.list.CatalogSyncVmFactory
import com.example.litreader.ui.list.ThreadListFragment
import com.example.litreader.util.UpdateManager

class MainActivity : AppCompatActivity() {
    private lateinit var bind: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bind.root)

        bind.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_lit -> { show(TAG_LIT); true }
                R.id.tab_img -> { show(TAG_IMG); true }
                R.id.tab_fav -> { show(TAG_FAV); true }
                else -> false
            }
        }
        if (savedInstanceState == null) {
            show(TAG_LIT)
            UpdateManager.checkAndPromptUpdate(this, lifecycleScope, silent = true)
        }

        // 每次进入 App：文学区目录增量同步（后台跑，首次为全量，不阻塞 UI）
        ViewModelProvider(
            this,
            CatalogSyncVmFactory(
                (application as App).database,
                getSharedPreferences("catalog", MODE_PRIVATE),
                SECTION_LIT
            )
        )[CatalogSyncViewModel::class.java].sync()
    }

    /** 各 Tab 常驻内存，切换只做 show/hide，保留滚动位置与分页状态。 */
    private fun show(tag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        listOf(TAG_LIT, TAG_IMG, TAG_FAV)
            .mapNotNull { fm.findFragmentByTag(it) }
            .forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing != null) {
            tx.show(existing)
        } else {
            tx.add(R.id.container, create(tag), tag)
        }
        tx.commit()
    }

    private fun create(tag: String): Fragment = when (tag) {
        TAG_IMG -> ThreadListFragment.newInstance(SECTION_IMG)
        TAG_FAV -> FavoritesFragment()
        else -> ThreadListFragment.newInstance(SECTION_LIT)
    }

    companion object {
        const val SECTION_LIT = "t66y_lit"
        const val SECTION_IMG = "t66y_img"
        private const val TAG_LIT = "lit"
        private const val TAG_IMG = "img"
        private const val TAG_FAV = "fav"
    }
}
