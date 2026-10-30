package com.example.litreader.ui.list

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.litreader.R
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.databinding.ItemThreadBinding
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
    private val showSection: Boolean = false
) : RecyclerView.Adapter<ThreadAdapter.VH>() {

    // 大结果集（书架收藏/搜索可达数百条）的 Diff 计算放后台，避免主线程卡顿。
    // 一致性约束：RecyclerView 可见的永远是 displayed 快照；submit 后必须等 diff
    // 在主线程 dispatchUpdatesTo 完成才换快照——否则计算窗口期里任何 re-layout
    // 都会让 getItemCount 与 RecyclerView 内部状态不一致而崩溃。
    private var displayed = listOf<ThreadEntity>()
    private var displayedProgress: Map<String, Float> = emptyMap()
    private val diffScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var diffJob: Job? = null

    /** 书架「最近」段：第 1 行用 accent 描边标记为「继续阅读」卡。 */
    var highlightFirst: Boolean = false

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

        val meta = mutableListOf(t.author)
        if (t.dateText.isNotEmpty()) meta += t.dateText
        if (t.replies.isNotEmpty()) meta += "${t.replies}回"
        h.b.tvMeta.text = meta.joinToString(" · ")

        // 已读/已读完视觉标识：已读完标题置灰 + 朱砂「已读完」，已读显示进度百分比
        val ctx = h.b.root.context
        when (t.readState) {
            2 -> {
                h.b.tvReadMark.visibility = View.VISIBLE
                h.b.tvReadMark.text = ctx.getString(R.string.filter_finished)
                h.b.tvReadMark.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                h.b.tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.inkSecondary))
            }
            1 -> {
                h.b.tvReadMark.visibility = View.VISIBLE
                val p = displayedProgress[t.tid]
                if (p != null && p > 0.005f) {
                    h.b.tvReadMark.text = ctx.getString(R.string.item_progress, (p * 100).toInt())
                } else {
                    h.b.tvReadMark.text = ctx.getString(R.string.filter_read)
                }
                h.b.tvReadMark.setTextColor(ContextCompat.getColor(ctx, R.color.inkSecondary))
                h.b.tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.inkPrimary))
            }
            else -> {
                h.b.tvReadMark.visibility = View.GONE
                h.b.tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.inkPrimary))
            }
        }

        h.b.imgFav.visibility = if (t.favorite) View.VISIBLE else View.GONE

        if (t.likes.isEmpty()) {
            h.b.tvLikes.visibility = View.GONE
        } else {
            h.b.tvLikes.visibility = View.VISIBLE
            h.b.tvLikes.text = t.likes
        }

        h.b.root.strokeColor = ContextCompat.getColor(
            ctx, if (highlightFirst && i == 0) R.color.accent else R.color.divider
        )
    }
}
