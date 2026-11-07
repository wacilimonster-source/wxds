package com.example.litreader.ui.fav

import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.view.Gravity
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
import com.example.litreader.data.repo.MarkSnapshot
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.databinding.FragmentFavoritesBinding
import com.example.litreader.ui.list.ThreadAdapter
import com.example.litreader.ui.main.MainActivity
import com.example.litreader.util.Haptics
import com.example.litreader.util.ReadProgress
import com.example.litreader.util.Skeleton
import com.example.litreader.util.UiTips
import java.util.concurrent.TimeUnit

/** 书架：最近浏览 + 收藏 + 不喜欢 三段共用一个列表；续读卡从最近记录派生，除不喜欢段外常驻。 */
class FavoritesFragment : Fragment() {
    private lateinit var bind: FragmentFavoritesBinding
    private lateinit var vm: FavoritesViewModel
    private lateinit var adapter: ThreadAdapter

    /** 0=收藏先后 1=帖子时间 2=标题（仅收藏段） */
    private var sortMode = 0
    /** 已读比例表：按次加载复用，避免 observer 与置顶卡各读一遍 prefs。 */
    private var progressMap: Map<String, Float> = emptyMap()

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
        adapter = ThreadAdapter(
            { open(it) }, { onLongPress(it) },
            onFavorite = { onFavoriteToggle(it) },
            onDislike = { onDislikeToggle(it) }
        )
        bind.recycler.layoutManager = LinearLayoutManager(requireContext())
        bind.recycler.setHasFixedSize(true)
        bind.recycler.adapter = adapter
        setupSwipe()
        progressMap = ReadProgress.map(requireContext())

        val prefs = requireActivity().getSharedPreferences("fav", Context.MODE_PRIVATE)
        sortMode = prefs.getInt("sort", 0)
        // 段与来源持久化：0=最近 1=收藏 2=不喜欢；首次安装默认停在收藏段
        vm.section = prefs.getInt("section", 1)
        vm.filter = prefs.getString("zone", "")?.takeIf { it.isNotEmpty() }

        bind.sectionGroup.check(
            when (vm.section) {
                2 -> R.id.btnSectionDislike
                0 -> R.id.btnSectionRecent
                else -> R.id.btnSectionFav
            }
        )
        bind.sectionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            Haptics.tick(bind.sectionGroup)
            val section = when (checkedId) {
                R.id.btnSectionDislike -> 2
                R.id.btnSectionRecent -> 0
                else -> 1
            }
            if (section != vm.section) {
                prefs.edit().putInt("section", section).apply()
                vm.selectSection(section)
                renderSectionUi()
                renderFilterChips()
            }
        }

        bind.tvFavSort.setOnClickListener { showSortMenu() }
        bind.tvFavClear.setOnClickListener { confirmClearRecents() }
        bind.btnContinue.setOnClickListener {
            vm.continueCandidate.value?.let { open(it) }
        }
        bind.btnEmptyAction.setOnClickListener {
            (requireActivity() as? MainActivity)?.switchTab(R.id.tab_lit)
        }
        renderSectionUi()
        renderFilterChips()

        vm.threads.observe(viewLifecycleOwner) { raw ->
            // 排序只作用于收藏段；最近/不喜欢段保持各自的时序倒序
            val list = if (vm.section == 1) sort(raw) else raw
            // 续读卡与最近列表同源：最近段去掉卡上那条避免重复；收藏段保留该项
            val hero = vm.continueCandidate.value
            val shown = if (vm.section == 0) list.filter { it.tid != hero?.tid } else list
            adapter.submit(shown, progressMap)
            bind.emptyBox.visibility = if (shown.isEmpty() && vm.loading.value != true) View.VISIBLE else View.GONE
            renderContinueCard(hero)
        }
        vm.continueCandidate.observe(viewLifecycleOwner) { hero ->
            val raw = vm.threads.value.orEmpty()
            val list = if (vm.section == 1) sort(raw) else raw
            val shown = if (vm.section == 0) list.filter { it.tid != hero?.tid } else list
            adapter.submit(shown, progressMap)
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
        vm.opFailure.observe(viewLifecycleOwner) { failed ->
            if (failed) {
                UiTips.snack(bind.root, getString(R.string.op_failed))
                vm.consumeOpFailure()
            }
        }
    }

    /** 续读卡：来源、读到百分比、按钮文案（开始阅读/继续阅读/再读一次）。 */
    private fun renderContinueCard(hero: ThreadEntity?) {
        val visible = hero != null && vm.section != 2
        bind.continueCard.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible || hero == null) return
        val sourceName = SourceRegistry.get(hero.sourceId)?.shortName ?: ""
        bind.tvContinueLabel.text = getString(
            R.string.continue_last_src, sourceName, relativeTime(hero.lastOpenAt)
        )
        bind.tvContinueTitle.text = hero.title
        if (hero.readState == 2) {
            // 已读完：不显示进度条，按钮=再读一次
            bind.continueProgress.visibility = View.GONE
            bind.tvContinuePercent.text = ""
            bind.btnContinue.text = getString(R.string.continue_reread)
        } else {
            val p = (progressMap[hero.tid] ?: 0f).coerceIn(0f, 1f)
            if (p > 0.005f) {
                bind.continueProgress.visibility = View.VISIBLE
                bind.continueProgress.progress = (p * 100).toInt()
                bind.tvContinuePercent.text = getString(R.string.item_progress, (p * 100).toInt())
            } else {
                // 缺进度：隐藏进度条，按钮=开始阅读
                bind.continueProgress.visibility = View.GONE
                bind.tvContinuePercent.text = ""
            }
            bind.btnContinue.text = getString(
                if (p > 0.005f) R.string.continue_reading else R.string.continue_start
            )
        }
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

    /** 区筛选 chips：全部 / 文学 / 贴图（zone 持久化到 fav/zone）。 */
    private fun renderFilterChips() {
        val prefs = requireActivity().getSharedPreferences("fav", Context.MODE_PRIVATE)
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
                minHeight = dp(48)
                gravity = Gravity.CENTER
                setOnClickListener {
                    Haptics.tick(this)
                    if (vm.filter != value) {
                        prefs.edit().putString("zone", value ?: "").apply()
                        vm.applyFilter(value)
                        renderFilterChips()
                    }
                }
            }
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
            ).apply { marginEnd = dp(8) }
            row.addView(tv)
        }
    }

    /** 段切换后的按钮与空态文案（列表数据由 vm 负责）。 */
    private fun renderSectionUi() {
        val dislike = vm.section == 2
        bind.tvFavSort.visibility = if (vm.section == 1) View.VISIBLE else View.GONE
        bind.tvFavClear.visibility = if (vm.section == 0) View.VISIBLE else View.GONE
        // 不喜欢段的空态没有「去逛逛」的引导意义，隐藏行动按钮
        bind.btnEmptyAction.visibility = if (dislike) View.GONE else View.VISIBLE
        when (vm.section) {
            2 -> {
                bind.tvEmptyTitle.setText(R.string.empty_dislike_title)
                bind.tvEmptyHint.setText(R.string.empty_dislike_hint)
            }
            0 -> {
                bind.tvEmptyTitle.setText(R.string.empty_recent_title)
                bind.tvEmptyHint.setText(R.string.empty_recent_hint)
            }
            else -> {
                bind.tvEmptyTitle.setText(R.string.empty_fav_title)
                bind.tvEmptyHint.setText(R.string.empty_fav_hint)
            }
        }
    }

    /** 清空最近：二次确认；失败提示重试，成功提供撤销（含继续卡即时消失）。 */
    private fun confirmClearRecents() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_recent)
            .setMessage(R.string.clear_recent_confirm)
            .setPositiveButton(R.string.clear_recent) { _, _ ->
                vm.clearRecents(
                    onCleared = { snap ->
                        UiTips.snack(
                            bind.root, getString(R.string.recent_cleared), getString(R.string.undo),
                            onAction = { vm.restoreRecents(snap) }
                        )
                    },
                    onFailed = {
                        UiTips.snack(bind.root, getString(R.string.op_failed))
                    }
                )
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

    /** 长按：收藏段/最近段=切换收藏；不喜欢段=取消不喜欢。均带可撤销轻提示。 */
    private fun onLongPress(t: ThreadEntity) {
        if (vm.section == 2) {
            val wasFavorite = t.favorite
            Haptics.tick(bind.root)
            vm.undoDislike(t.tid, wasFavorite)
            UiTips.snack(
                bind.root,
                getString(R.string.dislike_removed),
                getString(R.string.undo),
                onAction = { vm.dislike(t) { _, _ -> } }
            )
            return
        }
        val adding = !t.favorite
        Haptics.tick(bind.root)
        if (adding) Haptics.confirm(bind.root)
        vm.setFavoriteSnap(t, adding) { snap, v ->
            markSnack(t.tid, snap, v, getString(if (adding) R.string.fav_added else R.string.fav_removed))
        }
    }

    /** 收藏按钮：点星不进正文。 */
    private fun onFavoriteToggle(t: ThreadEntity) {
        Haptics.tick(bind.root)
        val target = !t.favorite
        vm.setFavoriteSnap(t, target) { snap, v ->
            markSnack(t.tid, snap, v, getString(if (target) R.string.fav_added else R.string.fav_removed))
        }
    }

    /** 不喜欢按钮：状态翻转 + 可撤销；不喜欢段内即取消不喜欢。 */
    private fun onDislikeToggle(t: ThreadEntity) {
        val target = !t.dislike
        if (target) Haptics.confirm(bind.root) else Haptics.tick(bind.root)
        vm.setDislikeSnap(t, target) { snap, v ->
            markSnack(t.tid, snap, v, getString(if (target) R.string.dislike_marked else R.string.dislike_removed))
        }
    }

    private fun markSnack(tid: String, snap: MarkSnapshot, version: Int, text: String) {
        UiTips.snack(
            bind.root, text, getString(R.string.undo),
            onAction = { vm.undoMark(tid, snap, version) }
        )
    }

    /** 左滑标记不喜欢（仅最近/收藏段；不喜欢段滑回原位不动作）。 */
    private fun setupSwipe() {
        val cb = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            0, androidx.recyclerview.widget.ItemTouchHelper.LEFT
        ) {
            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) = false

            override fun onChildDraw(
                c: Canvas,
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (vm.section == 2) {
                    super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                    return
                }
                if (dX < 0) {
                    val view = vh.itemView
                    val p = android.graphics.Paint().apply {
                        color = ContextCompat.getColor(requireContext(), R.color.danger)
                    }
                    c.drawRect(
                        view.right.toFloat() + dX, view.top.toFloat(),
                        view.right.toFloat(), view.bottom.toFloat(), p
                    )
                    val alpha = (255f * (-dX / view.width)).coerceIn(0f, 255f).toInt()
                    val text = getString(R.string.menu_act_dislike)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 14 * resources.displayMetrics.density
                        isAntiAlias = true
                        this.alpha = alpha
                    }
                    val textW = paint.measureText(text)
                    c.drawText(text, view.right - dp(24) - textW, view.centerY() + paint.textSize / 3, paint)
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }

            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
                val t = adapter.itemAt(pos) ?: return
                if (vm.section == 2) {
                    // 该段长按即取消，不提供滑动标记：弹回原位
                    adapter.notifyItemChanged(pos)
                    return
                }
                Haptics.confirm(bind.root)
                vm.dislike(t) { snap, v ->
                    markSnack(t.tid, snap, v, getString(R.string.dislike_marked))
                }
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(cb).attachToRecyclerView(bind.recycler)
    }

    private fun View.centerY(): Float = (top + bottom) / 2f

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        progressMap = ReadProgress.map(requireActivity())
        if (::vm.isInitialized) vm.load()
    }

    private fun open(t: ThreadEntity) = com.example.litreader.ui.ThreadNav.open(requireContext(), t)

}
