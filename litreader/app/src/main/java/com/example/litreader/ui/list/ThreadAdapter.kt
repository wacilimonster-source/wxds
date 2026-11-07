package com.example.litreader.ui.list

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.databinding.ItemListFooterBinding
import com.example.litreader.databinding.ItemThreadBinding
import com.example.litreader.util.ThumbLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 四页共用的帖子条目：标题 17sp、元信息 12sp、48dp 收藏/更多操作。
 * 收藏与更多按钮消费点击不触发条目导航；长按保留收藏快捷操作。
 * 列表页可启用尾部状态行（加载中/追加失败重试/已经到底了）。
 */
class ThreadAdapter(
    private val onClick: (ThreadEntity) -> Unit,
    private val onLongClick: (ThreadEntity) -> Unit,
    /** 收藏按钮（48dp，选中态随 favorite 翻转；点击不进入正文） */
    private val onFavorite: (ThreadEntity) -> Unit = {},
    /** 不喜欢按钮（48dp，已不喜欢为实心 danger；点击不进入正文） */
    private val onDislike: (ThreadEntity) -> Unit = {},
    /** 搜索结果等跨区场景下，在条目里标注所属区 */
    private val showSection: Boolean = false,
    /** 贴图列表才显示首图缩略（文学列表与搜索结果保持纯文字规格） */
    private val showThumb: Boolean = false,
    /** 启用列表尾部状态行（仅列表页） */
    private val withFooter: Boolean = false,
    /** 尾部重试点击（仅 withFooter 时生效） */
    private val onRetry: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 尾部状态：无 / 加载中 / 失败重试 / 已到底。 */
    enum class FooterState { NONE, LOADING, RETRY, END }

    private var footer: FooterState = FooterState.NONE
    private val hasFooterRow: Boolean get() = withFooter && footer != FooterState.NONE

    /** 尾部状态切换（位置随 displayed 快照移动）。 */
    fun setFooter(state: FooterState) {
        if (footer == state) return
        val had = hasFooterRow
        footer = state
        val has = hasFooterRow
        when {
            had && has -> notifyItemChanged(displayed.size)
            had && !has -> notifyItemRemoved(displayed.size)
            !had && has -> notifyItemInserted(displayed.size)
        }
    }

    // 大结果集（书架收藏/搜索可达数百条）的 Diff 计算放后台，避免主线程卡顿。
    // 一致性约束：RecyclerView 可见的永远是 displayed 快照；submit 后必须等 diff
    // 在主线程 dispatchUpdatesTo 完成才换快照——否则计算窗口期里任何 re-layout
    // 都会让 getItemCount 与 RecyclerView 内部状态不一致而崩溃。
    private var displayed = listOf<ThreadEntity>()
    private var displayedProgress: Map<String, Float> = emptyMap()

    /** 滑动/长按回调按位置取条目（越界返回 null）。 */
    fun itemAt(position: Int): ThreadEntity? = displayed.getOrNull(position)
    private val diffScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var diffJob: Job? = null

    /** 绑定期反复用到的颜色：每个条目多次刷新时不再做资源查找。 */
    private class Palette(ctx: Context) {
        val inkPrimary = ContextCompat.getColor(ctx, R.color.inkPrimary)
        val inkSecondary = ContextCompat.getColor(ctx, R.color.inkSecondary)
        val ink3 = ContextCompat.getColor(ctx, R.color.ink3)
        val success = ContextCompat.getColor(ctx, R.color.success)
        val accent = ContextCompat.getColor(ctx, R.color.accent)
        val danger = ContextCompat.getColor(ctx, R.color.danger)
    }

    private var palette: Palette? = null

    fun submit(list: List<ThreadEntity>) = submit(list, displayedProgress)

    fun submit(list: List<ThreadEntity>, progressMap: Map<String, Float>) {
        val old = displayed
        val oldProgress = displayedProgress
        diffJob?.cancel()
        diffJob = diffScope.launch {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].tid == list[n].tid
                override fun areContentsTheSame(o: Int, n: Int) =
                    old[o] == list[n] && oldProgress[old[o].tid] == progressMap[list[n].tid]
            })
            withContext(Dispatchers.Main) {
                val hadFooter = hasFooterRow
                displayed = list
                displayedProgress = progressMap
                diff.dispatchUpdatesTo(this@ThreadAdapter)
                // 尾部行跟随快照位置移动（数据结构变化已由 diff 派发，此处只补足差量）
                val hasNow = hasFooterRow
                if (hadFooter != hasNow) {
                    if (hasNow) notifyItemInserted(displayed.size) else notifyItemRemoved(displayed.size)
                }
            }
        }
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        diffScope.cancel()
        super.onDetachedFromRecyclerView(rv)
    }

    inner class VH(val b: ItemThreadBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            // 按压缩放反馈：DOWN 缩到 0.98，松手回弹；点击照常派发
            b.root.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f)
                        .setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                }
                false
            }
            b.root.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) onClick(displayed[adapterPosition])
            }
            b.root.setOnLongClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onLongClick(displayed[adapterPosition]); true
                } else false
            }
            // 收藏/更多消费点击，条目点击只触发一次导航
            b.imgFav.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) onFavorite(displayed[adapterPosition])
            }
            b.btnDislike.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) onDislike(displayed[adapterPosition])
            }
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, v: Int): RecyclerView.ViewHolder =
        if (v == TYPE_FOOTER) {
            FooterVH(ItemListFooterBinding.inflate(LayoutInflater.from(p.context), p, false))
        } else {
            VH(ItemThreadBinding.inflate(LayoutInflater.from(p.context), p, false))
        }

    override fun getItemViewType(position: Int): Int =
        if (hasFooterRow && position == displayed.size) TYPE_FOOTER else TYPE_ITEM

    override fun getItemCount() = displayed.size + if (hasFooterRow) 1 else 0

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
        if (h is FooterVH) {
            h.bind(footer)
            return
        }
        h as VH
        val t = displayed[i]
        val ctx = h.b.root.context
        val pal = palette ?: Palette(ctx).also { palette = it }

        h.b.tvTitle.text = t.title

        if (showSection) {
            val section = SourceRegistry.get(t.sourceId)?.shortName ?: ""
            if (section.isNotEmpty()) {
                h.b.tvTag.visibility = View.VISIBLE
                h.b.tvTag.text = section
            } else {
                h.b.tvTag.visibility = View.GONE
            }
        } else if (t.tag.isEmpty()) {
            h.b.tvTag.visibility = View.GONE
        } else {
            h.b.tvTag.visibility = View.VISIBLE
            h.b.tvTag.text = t.tag
        }

        val meta = buildString {
            if (t.author.isNotEmpty()) append(t.author)
            if (t.dateText.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(t.dateText)
            }
        }
        h.b.tvMeta.text = meta
        h.b.tvMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

        // 带名称的回复/赞数；未知赞数（时间文本/空串）隐藏，避免展示没有语义的数字
        val counts = buildString {
            if (t.replies.isNotEmpty()) append(t.replies).append(" ").append(ctx.getString(R.string.item_replies_unit))
            if (likeCount(t.likes) > 0) {
                if (isNotEmpty()) append(" · ")
                append(t.likes).append(" ").append(ctx.getString(R.string.item_likes_unit))
            }
        }
        h.b.tvCounts.text = counts
        h.b.tvCounts.visibility = if (counts.isEmpty()) View.GONE else View.VISIBLE

        // 已打开/已读完/进度：标题置灰 + 徽标 + 底部 2px 进度细线（已读完走 success 色满条）
        val progress = displayedProgress[t.tid]
        when (t.readState) {
            2 -> {
                h.b.tvReadMark.visibility = View.VISIBLE
                h.b.tvReadMark.text = ctx.getString(R.string.filter_finished)
                h.b.tvReadMark.setTextColor(pal.success)
                bindReadMarkIcon(h, pal.success, finished = true)
                h.b.tvTitle.setTextColor(pal.ink3)
                h.b.progressLine.visibility = View.VISIBLE
                h.b.progressLine.progressDrawable = ContextCompat.getDrawable(ctx, R.drawable.bg_progress_thin_done)
                h.b.progressLine.progress = 100
            }
            1 -> {
                h.b.tvReadMark.visibility = View.VISIBLE
                if (progress != null && progress > 0.005f) {
                    h.b.tvReadMark.text = ctx.getString(R.string.item_progress, (progress * 100).toInt())
                } else {
                    h.b.tvReadMark.text = ctx.getString(R.string.read_opened)
                }
                h.b.tvReadMark.setTextColor(pal.inkSecondary)
                bindReadMarkIcon(h, pal.inkSecondary, finished = false)
                h.b.tvTitle.setTextColor(pal.ink3)
                if (progress != null && progress > 0.005f) {
                    h.b.progressLine.visibility = View.VISIBLE
                    h.b.progressLine.progressDrawable = ContextCompat.getDrawable(ctx, R.drawable.bg_progress_thin)
                    h.b.progressLine.progress = (progress * 100).toInt()
                } else {
                    h.b.progressLine.visibility = View.GONE
                }
            }
            else -> {
                h.b.tvReadMark.visibility = View.GONE
                h.b.tvReadMark.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                h.b.tvTitle.setTextColor(pal.inkPrimary)
                h.b.progressLine.visibility = View.GONE
            }
        }

        // 收藏/不喜欢按钮：始终可见、可点击；选中态=实心 + 无障碍描述
        h.b.imgFav.setImageResource(if (t.favorite) R.drawable.ic_star_fill else R.drawable.ic_star_outline)
        h.b.imgFav.setColorFilter(if (t.favorite) pal.accent else pal.inkSecondary)
        h.b.favBg.visibility = if (t.favorite) View.VISIBLE else View.INVISIBLE
        h.b.imgFav.contentDescription = ctx.getString(
            if (t.favorite) R.string.item_unfavorite_cd else R.string.item_favorite_cd
        )
        h.b.btnDislike.setColorFilter(if (t.dislike) pal.danger else pal.inkSecondary)
        h.b.btnDislike.contentDescription = ctx.getString(
            if (t.dislike) R.string.item_undislike_cd else R.string.item_dislike_cd
        )

        bindThumb(h, t)
        // 复用视图可能停在被按下的缩放态；复位只在实际缩放过时执行
        if (h.b.root.scaleX != 1f || h.b.root.scaleY != 1f) {
            h.b.root.scaleX = 1f
            h.b.root.scaleY = 1f
        }
    }

    /** 阅读徽标前缀图标：已读完对勾，已打开/进度小圆点；颜色跟随徽标文字色。 */
    private fun bindReadMarkIcon(h: VH, color: Int, finished: Boolean) {
        val ctx = h.b.root.context
        val icon = ContextCompat.getDrawable(ctx, if (finished) R.drawable.ic_read_check else R.drawable.read_dot)
            ?.mutate()?.also { DrawableCompat.setTint(it, color) }
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(h.b.tvReadMark, icon, null, null, null)
    }

    /** 贴图 72dp 缩略：无图图标占位，失败显示可见重试；加载策略沿用 ThumbLoader 缓存。 */
    private fun bindThumb(h: VH, t: ThreadEntity) {
        val wrap = h.b.thumbWrap
        if (!showThumb) {
            (h.b.imgThumb.getTag(R.id.thumbJob) as? Job)?.cancel()
            wrap.visibility = View.GONE
            return
        }
        wrap.visibility = View.VISIBLE
        val iv = h.b.imgThumb
        val placeholder = h.b.thumbPlaceholder
        val retry = h.b.thumbRetry
        retry.setOnClickListener { v ->
            v.visibility = View.GONE
            placeholderIconOnly(h)
            t.thumb.takeIf { it.isNotEmpty() }?.let { loadThumb(h, it) }
        }
        if (t.thumb.isEmpty()) {
            (iv.getTag(R.id.thumbJob) as? Job)?.cancel()
            iv.setImageDrawable(null)
            iv.alpha = 1f
            placeholder.visibility = View.VISIBLE
            h.b.thumbPlaceholderIcon.visibility = View.VISIBLE
            retry.visibility = View.GONE
            return
        }
        placeholderIconOnly(h)
        loadThumb(h, t.thumb)
    }

    /** 占位层必须可见：它覆盖在缩略图之上，加载成功后要整层隐藏才能露出图片。 */
    private fun placeholderIconOnly(h: VH) {
        h.b.imgThumb.setImageDrawable(null)
        h.b.imgThumb.alpha = 1f
        h.b.thumbPlaceholder.visibility = View.VISIBLE
        h.b.thumbPlaceholderIcon.visibility = View.VISIBLE
        h.b.thumbRetry.visibility = View.GONE
    }

    private fun loadThumb(h: VH, url: String) {
        val iv: ImageView = h.b.imgThumb
        iv.alpha = 0f
        h.b.thumbPlaceholder.visibility = View.VISIBLE
        h.b.thumbPlaceholderIcon.visibility = View.VISIBLE
        h.b.thumbRetry.visibility = View.GONE
        ThumbLoader.load(url, iv) { ok ->
            if (ok) {
                h.b.thumbPlaceholder.visibility = View.GONE
            } else {
                iv.setImageDrawable(null)
                iv.alpha = 1f
                h.b.thumbPlaceholder.visibility = View.VISIBLE
                h.b.thumbPlaceholderIcon.visibility = View.VISIBLE
                h.b.thumbRetry.visibility = View.VISIBLE
            }
        }
    }

    /** '157'→157、'3.4K'→3400；'2 小時' 这类时间文本与空串视作 0（不展示）。 */
    private fun likeCount(raw: String): Int {
        val m = Regex("""^([\d.]+)\s*([KkMm]?)$""").find(raw.trim()) ?: return 0
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0
        return when (m.groupValues[2].uppercase()) {
            "K" -> (n * 1000).toInt()
            "M" -> (n * 1_000_000).toInt()
            else -> n.toInt()
        }
    }

    private inner class FooterVH(val b: ItemListFooterBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.tvFooterRetry.setOnClickListener { onRetry() }
        }

        fun bind(state: FooterState) {
            when (state) {
                FooterState.LOADING -> {
                    b.footerProgress.visibility = View.VISIBLE
                    b.tvFooterText.setText(R.string.footer_loading)
                    b.tvFooterText.visibility = View.VISIBLE
                    b.tvFooterRetry.visibility = View.GONE
                }
                FooterState.RETRY -> {
                    b.footerProgress.visibility = View.GONE
                    b.tvFooterText.visibility = View.GONE
                    b.tvFooterRetry.visibility = View.VISIBLE
                }
                FooterState.END -> {
                    b.footerProgress.visibility = View.GONE
                    b.tvFooterText.setText(R.string.footer_end)
                    b.tvFooterText.visibility = View.VISIBLE
                    b.tvFooterRetry.visibility = View.GONE
                }
                FooterState.NONE -> {}
            }
        }
    }

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_FOOTER = 1
    }
}
