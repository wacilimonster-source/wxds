package com.example.litreader.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.databinding.ActivityMainBinding
import com.example.litreader.ui.browse.BrowseFragment
import com.example.litreader.ui.fav.FavoritesFragment
import com.example.litreader.ui.list.CatalogSyncViewModel
import com.example.litreader.ui.list.CatalogSyncVmFactory
import com.example.litreader.ui.search.SearchFragment
import com.example.litreader.util.UpdateManager

class MainActivity : AppCompatActivity() {
    private lateinit var bind: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bind.root)

        bind.bottomNav.setOnItemSelectedListener { item ->
            com.example.litreader.util.Haptics.tick(bind.bottomNav)
            when (item.itemId) {
                R.id.tab_browse -> { show(TAG_BROWSE); true }
                R.id.tab_fav -> { show(TAG_FAV); true }
                R.id.tab_search -> { show(TAG_SEARCH); true }
                else -> false
            }
        }
        if (savedInstanceState == null) {
            show(TAG_BROWSE)
            UpdateManager.checkAndPromptUpdate(this, lifecycleScope, silent = true)
        }
        setupBackNavigation()

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
        listOf(TAG_BROWSE, TAG_FAV, TAG_SEARCH)
            .mapNotNull { fm.findFragmentByTag(it) }
            .forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing != null) {
            tx.show(existing)
        } else {
            tx.add(R.id.container, create(tag), tag)
        }
        backToBrowse?.isEnabled = tag != TAG_BROWSE
        tx.commit()
    }

    /** 列表页放大镜入口：切到搜索页签并附带当前来源；focus=true 时聚焦输入框并弹键盘。 */
    fun openSearch(sourceId: String, focus: Boolean) {
        switchTab(R.id.tab_search)
        (supportFragmentManager.findFragmentByTag(TAG_SEARCH) as? SearchFragment)?.apply {
            presetSource(sourceId)
            if (focus) focusInput()
        }
    }

    /** 供空态行动按钮等跨页签跳转使用（幂等：已在目标页签则不动）。 */
    fun switchTab(itemId: Int) {
        if (bind.bottomNav.selectedItemId != itemId) {
            bind.bottomNav.selectedItemId = itemId
            supportFragmentManager.executePendingTransactions()
        }
    }

    /** 返回键：书架/搜索页先回浏览页，浏览页再按才退出（避免误退）。 */
    private var backToBrowse: androidx.activity.OnBackPressedCallback? = null

    private fun setupBackNavigation() {
        val callback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (bind.bottomNav.selectedItemId != R.id.tab_browse) {
                    bind.bottomNav.selectedItemId = R.id.tab_browse
                } else {
                    // 回浏览页后再按：交回系统（退出 App）
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        backToBrowse = callback
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun create(tag: String): Fragment = when (tag) {
        TAG_FAV -> FavoritesFragment()
        TAG_SEARCH -> SearchFragment()
        else -> BrowseFragment()
    }

    /** 跨页跳转到指定分区（书架空态「去文学看看」等）：切浏览页签并选中分区。 */
    fun openSection(sectionId: String) {
        switchTab(R.id.tab_browse)
        (supportFragmentManager.findFragmentByTag(TAG_BROWSE) as? BrowseFragment)?.select(sectionId)
    }

    companion object {
        const val SECTION_LIT = "t66y_lit"
        const val SECTION_IMG = "t66y_img"
        const val SECTION_CINE = "t66y_cine"
        const val SECTION_TECH = "t66y_tech"
        private const val TAG_BROWSE = "browse"
        private const val TAG_FAV = "fav"
        private const val TAG_SEARCH = "search"
    }
}
