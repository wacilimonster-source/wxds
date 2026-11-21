package com.example.litreader.ui.browse

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.litreader.R
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.databinding.FragmentBrowseBinding
import com.example.litreader.ui.list.ThreadListFragment
import com.example.litreader.ui.main.MainActivity
import com.google.android.material.tabs.TabLayoutMediator

/**
 * 浏览页：文学 / 贴图 / 影院 / 技术 四分区 Tab + ViewPager2。
 * BottomNavigationView 上限 5 项，分区切换收进顶部条；底部导航保留 浏览/书架/搜索。
 * offscreenPageLimit=3 全分区常驻（与旧 show/hide 语义一致，滚动/分页状态保留）；
 * 禁用横滑翻页——列表行左滑「不喜欢」是核心手势，切区只走 tab 点击。
 * 最后浏览的分区持久化，下次进 App 回到原分区。
 */
class BrowseFragment : Fragment() {
    private lateinit var bind: FragmentBrowseBinding
    private val sections = listOf(
        MainActivity.SECTION_LIT,
        MainActivity.SECTION_IMG,
        MainActivity.SECTION_CINE,
        MainActivity.SECTION_TECH
    )
    /** openSection 在 view 未就绪时的暂存目标。 */
    private var pendingSelect: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        bind = FragmentBrowseBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bind.sectionPager.adapter = SectionsAdapter()
        bind.sectionPager.offscreenPageLimit = sections.size - 1
        bind.sectionPager.isUserInputEnabled = false
        TabLayoutMediator(bind.sectionTabs, bind.sectionPager, false, false) { tab, pos ->
            tab.text = SourceRegistry.get(sections[pos])?.shortName ?: ""
        }.attach()
        // 分区懒加载：只有切到该分区才发首屏请求（首屏四区并发抓取会拖慢启动）
        bind.sectionPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                activateSection(position)
            }
        })
        bind.sectionTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                selectInternal(sections.getOrNull(tab.position) ?: return)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
        val last = prefs().getString(PREF_LAST, null) ?: sections.first()
        selectInternal(last)
        pendingSelect?.let { selectInternal(it); pendingSelect = null }
    }

    /** 触达分区：驱动 ViewPager 到该页并激活列表（首次可见才请求）。 */
    private fun activateSection(position: Int) {
        if (bind.sectionPager.currentItem != position) bind.sectionPager.setCurrentItem(position, false)
        val id = sections.getOrNull(position) ?: return
        bind.root.post {
            childFragmentManager.fragments
                .filterIsInstance<ThreadListFragment>()
                .firstOrNull { it.sectionId() == id }
                ?.activate()
        }
    }

    /** 跨页跳转入口（书架空态「去文学看看」等）：切到指定分区。 */
    fun select(sectionId: String) {
        if (!this::bind.isInitialized) {
            pendingSelect = sectionId
            return
        }
        selectInternal(sectionId)
    }

    private fun selectInternal(sectionId: String) {
        val idx = sections.indexOf(sectionId)
        if (idx < 0) return
        bind.sectionPager.setCurrentItem(idx, false)
        activateSection(idx)
        prefs().edit().putString(PREF_LAST, sectionId).apply()
    }

    private fun prefs() =
        requireContext().getSharedPreferences("browse", Context.MODE_PRIVATE)

    /** 全分区常驻：与旧 show/hide 的「保留滚动与分页状态」对齐；首屏请求由激活驱动。 */
    private inner class SectionsAdapter : FragmentStateAdapter(this) {
        override fun getItemCount() = sections.size
        override fun createFragment(position: Int) =
            ThreadListFragment.newInstance(sections[position], lazy = true)
    }

    companion object {
        private const val PREF_LAST = "last_section"
    }
}
