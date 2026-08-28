package com.example.litreader.ui.list

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.databinding.ItemThreadBinding

class ThreadAdapter(
    private val onClick: (ThreadEntity) -> Unit,
    private val onLongClick: (ThreadEntity) -> Unit,
    /** 搜索结果等跨区场景下，在条目里标注所属区 */
    private val showSection: Boolean = false
) : RecyclerView.Adapter<ThreadAdapter.VH>() {

    private var data = listOf<ThreadEntity>()

    fun submit(list: List<ThreadEntity>) {
        val old = data
        data = list
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].tid == list[n].tid
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        }).dispatchUpdatesTo(this)
    }

    inner class VH(val b: ItemThreadBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnClickListener { if (adapterPosition != RecyclerView.NO_POSITION) onClick(data[adapterPosition]) }
            b.root.setOnLongClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) { onLongClick(data[adapterPosition]); true } else false
            }
        }
    }

    override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
        VH(ItemThreadBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val t = data[i]
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

        h.b.imgFav.visibility = if (t.favorite) View.VISIBLE else View.GONE

        if (t.likes.isEmpty()) {
            h.b.tvLikes.visibility = View.GONE
        } else {
            h.b.tvLikes.visibility = View.VISIBLE
            h.b.tvLikes.text = t.likes
        }
    }
}
