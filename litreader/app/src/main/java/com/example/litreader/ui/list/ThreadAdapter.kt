package com.example.litreader.ui.list

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.databinding.ItemThreadBinding
import com.example.litreader.util.ThumbLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreadAdapter(
    private val onClick: (ThreadEntity) -> Unit,
    private val onLongClick: (ThreadEntity) -> Unit,
    /** 搜索结果等跨区场景下，在条目里标注所属区 */
    private val showSection: Boolean = false,
    /** 贴图列表才显示首图缩略（文学列表与搜索结果保持纯文字规格） */
    private val showThumb: Boolean = false
) : RecyclerView.Adapter<ThreadAdapter.VH>() {

    // 大结果集（书架收藏/搜索可达数百条）的 Diff 计算放后台，避免主线程卡顿。
    // 一致性约束：RecyclerView 可见的永远是 displayed 快照；submit 后必须等 diff
    // 在主线程 dispatchUpdatesTo 完成才换快照——否则计算窗口期里任何 re-layout
    // 都会让 getItemCount 与 RecyclerView 内部状态不一致而崩溃。
    private var displayed = listOf<ThreadEntity>()
    private var displayedProgress: Map<String, Float> = emptyMap()
    private val diffScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var diffJob: Job? = null

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
                displayed = list
                displayedProgress = progressMap
                diff.dispatchUpdatesTo(this@ThreadAdapter)
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
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
        VH(ItemThreadBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = displayed.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val t = displayed[i]
        val ctx = h.b.root.context

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

        val meta = mutableListOf<String>()
        if (t.author.isNotEmpty()) meta += t.author
        if (t.dateText.isNotEmpty()) meta += t.dateText
        if (t.replies.isNotEmpty()) meta += "${t.replies}回"
        h.b.tvMeta.text = meta.joinToString(" · ")
        h.b.tvMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

        // 已读/已读完：标题置灰 + 徽标 + 底部 2px 进度细线（已读完走 success 色满条）
        val progress = displayedProgress[t.tid]
        when (t.readState) {
            2 -> {
                h.b.tvReadMark.visibility = View.VISIBLE
                h.b.tvReadMark.text = ctx.getString(R.string.filter_finished)
                h.b.tvReadMark.setTextColor(ContextCompat.getColor(ctx, R.color.success))
                h.b.tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.ink3))
                h.b.progressLine.visibility = View.VISIBLE
                h.b.progressLine.progressDrawable = ContextCompat.getDrawable(ctx, R.drawable.bg_progress_thin_done)
                h.b.progressLine.progress = 100
            }
            1 -> {
                h.b.tvReadMark.visibility = View.VISIBLE
                if (progress != null && progress > 0.005f) {
                    h.b.tvReadMark.text = ctx.getString(R.string.item_progress, (progress * 100).toInt())
                } else {
                    h.b.tvReadMark.text = ctx.getString(R.string.filter_read)
                }
                h.b.tvReadMark.setTextColor(ContextCompat.getColor(ctx, R.color.inkSecondary))
                h.b.tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.ink3))
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
                h.b.tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.inkPrimary))
                h.b.progressLine.visibility = View.GONE
            }
        }

        h.b.imgFav.visibility = if (t.favorite) View.VISIBLE else View.GONE

        if (t.likes.isEmpty()) {
            h.b.tvLikes.visibility = View.GONE
        } else {
            h.b.tvLikes.visibility = View.VISIBLE
            h.b.tvLikes.text = t.likes
        }

        // 贴图首图缩略：打开过（正文已缓存）的帖子显示，加载完成后 200ms 淡入
        if (showThumb && t.thumb.isNotEmpty()) {
            h.b.imgThumb.visibility = View.VISIBLE
            h.b.imgThumb.alpha = 0f
            ThumbLoader.load(t.thumb, h.b.imgThumb)
        } else {
            (h.b.imgThumb.getTag(R.id.thumbJob) as? Job)?.cancel()
            h.b.imgThumb.visibility = View.GONE
            h.b.imgThumb.setImageDrawable(null)
        }

        h.b.root.strokeColor = ContextCompat.getColor(ctx, R.color.divider)
        h.b.root.scaleX = 1f
        h.b.root.scaleY = 1f
    }
}
