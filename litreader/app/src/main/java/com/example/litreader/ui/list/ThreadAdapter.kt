package com.example.litreader.ui.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.ItemThreadBinding

class ThreadAdapter(
    private val onClick: (ThreadEntity) -> Unit,
    private val onLongClick: (ThreadEntity) -> Unit
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
        val parts = mutableListOf<String>()
        if (t.tag.isNotEmpty()) parts += t.tag
        parts += t.author
        if (t.dateText.isNotEmpty()) parts += t.dateText
        h.b.tvMeta.text = parts.joinToString(" · ")
        h.b.tvLikes.visibility = if (t.likes.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        h.b.tvLikes.text = t.likes
        h.b.tvFav.visibility = if (t.favorite) android.view.View.VISIBLE else android.view.View.GONE
    }
}
