package com.example.litreader.ui.fav

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.FragmentFavoritesBinding
import com.example.litreader.ui.list.ThreadAdapter
import com.example.litreader.ui.main.MainActivity
import com.example.litreader.util.Haptics
import com.example.litreader.util.ReadProgress
import com.example.litreader.util.Skeleton
import com.example.litreader.util.UiTips
import java.util.concurrent.TimeUnit

/** 书架：最近浏览 + 收藏 两段共用一个列表，段内筛选/排序独立；最近段置顶「继续阅读」卡。 */
class FavoritesFragment : Fragment() {
    private lateinit var bind: FragmentFavoritesBinding
    private lateinit var vm: FavoritesViewModel
    private lateinit var adapter: ThreadAdapter

    /** 0=收藏先后 1=帖子时间 2=标题（仅收藏段） */
    private var sortMode = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        bind = FragmentFavoritesBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm = ViewModelProvider(
            this, FavoritesVmFactory((requireActivity().application as App).database)
        )[FavoritesViewModel::class.java]
        adapter = ThreadAdapter({ open(it) }, { onLongPress(it) })
        bind.recycler.layoutManager = LinearLayoutManager(requireContext())
        bind.recycler.adapter = adapter

        val prefs = requireActivity().getSharedPreferences("fav", Context.MODE_PRIVATE)
        sortMode = prefs.getInt("sort", 0)
        // 段持久化：0=最近 1=收藏；首次安装默认停在收藏段
        vm.sectionFavorites = prefs.getInt("section", 1) == 1

        bind.sectionGroup.check(if (vm.sectionFavorites) R.id.btnSectionFav else R.id.btnSectionRecent)
        bind.sectionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            Haptics.tick(bind.sectionGroup)
            val favorites = checkedId == R.id.btnSectionFav
            if (favorites != vm.sectionFavorites) {
                prefs.edit().putInt("section", if (favorites) 1 else 0).apply()
                vm.setSection(favorites)
                renderSectionUi()
                renderFilterChips()
            }
        }

        bind.tvFavSort.setOnClickListener { showSortMenu() }
        bind.tvFavClear.setOnClickListener { confirmClearRecents() }
        bind.btnContinue.setOnClickListener { vm.recent.value?.let { open(it) } }
        bind.btnEmptyAction.setOnClickListener {
            (requireActivity() as? MainActivity)?.switchTab(R.id.tab_lit)
        }
        renderSectionUi()
        renderFilterChips()

        vm.threads.observe(viewLifecycleOwner) { list ->
            // 继续阅读卡与列表同源：取列表首项做置顶卡，并从列表中移除以避免重复。
            // （不要读第二个 LiveData，两个 observer 的触发时序会让去重失效）
            val hero = if (!vm.sectionFavorites && vm.filter == null) list.firstOrNull() else null
            val shown = if (hero != null) list.drop(1) else list
            adapter.submit(shown, ReadProgress.map(requireActivity()))
            bind.emptyBox.visibility = if (shown.isEmpty() && vm.loading.value != true) View.VISIBLE else View.GONE
            renderContinueCard(hero)
        }
        vm.loading.observe(viewLifecycleOwner) { loading ->
            // 与列表页同款竞态修复：以 VM 数据为准，不读 adapter.itemCount
            val empty = vm.threads.value.isNullOrEmpty()
            if (loading && empty) {
                Skeleton.show(bind.skeleton.root)
                bind.emptyBox.visibility = View.GONE
            } else {
                Skeleton.hide(bind.skeleton.root)
                if (!loading) {
                    bind.emptyBox.visibility = if (empty) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** 继续阅读卡：仅最近段 + 全部区显示，内容取最新浏览记录。 */
    private fun renderContinueCard(hero: ThreadEntity?) {
        val visible = hero != null
        bind.continueCard.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible || hero == null) return
        bind.tvContinueLabel.text = getString(R.string.continue_last, relativeTime(hero.lastOpenAt))
        bind.tvContinueTitle.text = hero.title
        val p = (ReadProgress.map(requireActivity())[hero.tid] ?: 0f).coerceIn(0f, 1f)
        bind.continueProgress.progress = (p * 100).toInt()
        bind.tvContinuePercent.text =
            if (p > 0.005f) getString(R.string.item_progress, (p * 100).toInt())
            else getString(R.string.reader_start_hint)
    }

    private fun relativeTime(ts: Long): String {
        if (ts <= 0) return ""
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> getString(R.string.time_just_now)
            diff < TimeUnit.HOURS.toMillis(1) ->
                getString(R.string.time_minutes, TimeUnit.MILLISECONDS.toMinutes(diff).toInt())
            diff < TimeUnit.DAYS.toMillis(1) ->
                getString(R.string.time_hours, TimeUnit.MILLISECONDS.toHours(diff).toInt())
            else -> getString(R.string.time_days, TimeUnit.MILLISECONDS.toDays(diff).toInt())
        }
    }

    /** 区筛选 chips：全部 / 文学 / 贴图。 */
    private fun renderFilterChips() {
        val row = bind.filterRow
        row.removeAllViews()
        val options = listOf(
            null to getString(R.string.filter_all),
            MainActivity.SECTION_LIT to getString(R.string.tab_lit),
            MainActivity.SECTION_IMG to getString(R.string.tab_img)
        )
        options.forEach { (value, label) ->
            val tv = TextView(requireContext(), null, 0, R.style.LitChip).apply {
                text = label
                isSelected = vm.filter == value
                setOnClickListener {
                    Haptics.tick(this)
                    if (vm.filter != value) {
                        vm.applyFilter(value)
                        renderFilterChips()
                    }
                }
            }
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            row.addView(tv)
        }
    }

    /** 段切换后的按钮与空态文案（列表数据由 vm 负责）。 */
    private fun renderSectionUi() {
        val favorites = vm.sectionFavorites
        bind.tvFavSort.visibility = if (favorites) View.VISIBLE else View.GONE
        bind.tvFavClear.visibility = if (favorites) View.GONE else View.VISIBLE
        bind.tvEmptyTitle.setText(
            if (favorites) R.string.empty_fav_title else R.string.empty_recent_title
        )
        bind.tvEmptyHint.setText(
            if (favorites) R.string.empty_fav_hint else R.string.empty_recent_hint
        )
    }

    private fun confirmClearRecents() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_recent)
            .setMessage(R.string.clear_recent_confirm)
            .setPositiveButton(R.string.clear_recent) { _, _ ->
                vm.clearRecents { snap ->
                    UiTips.snack(
                        bind.root, getString(R.string.recent_cleared), getString(R.string.undo),
                        onAction = { vm.restoreRecents(snap) }
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 排序菜单：收藏先后 / 帖子时间 / 标题，选择持久化（仅收藏段）。 */
    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), bind.tvFavSort)
        val labels = listOf(
            R.string.fav_sort_fav,
            R.string.fav_sort_time,
            R.string.fav_sort_title
        )
        labels.forEachIndexed { i, res ->
            popup.menu.add(0, i, i, getString(res)).isChecked = sortMode == i
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener {
            if (sortMode != it.itemId) {
                sortMode = it.itemId
                requireActivity().getSharedPreferences("fav", Context.MODE_PRIVATE)
                    .edit().putInt("sort", sortMode).apply()
                vm.load()
            }
            true
        }
        popup.show()
    }

    private fun sort(list: List<ThreadEntity>): List<ThreadEntity> = when (sortMode) {
        1 -> list.sortedByDescending { it.timestamp }
        2 -> list.sortedBy { it.title }
        else -> list.sortedWith(
            compareByDescending<ThreadEntity> { it.favoriteAt }.thenByDescending { it.timestamp }
        )
    }

    /** 长按收藏：状态翻转 + 轻提示（可撤销）+ 成功节奏触感。 */
    private fun onLongPress(t: ThreadEntity) {
        val adding = !t.favorite
        vm.toggleFavorite(t)
        if (adding) Haptics.confirm(bind.root)
        UiTips.snack(
            bind.root,
            getString(if (adding) R.string.fav_added else R.string.fav_removed),
            getString(R.string.undo),
            onAction = { vm.toggleFavorite(t.copy(favorite = adding)) }
        )
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.load()
    }

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(requireContext(), t)
}
