package com.example.litreader.ui.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.databinding.ItemThreadBinding

class ThreadAdapter(private val onClick: (ThreadEntity) -> Unit) :
    RecyclerView.Adapter<ThreadAdapter.VH>() {

    private var data = listOf<ThreadEntity>()

    fun submit(list: List<ThreadEntity>) {
        data = list
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemThreadBinding) : RecyclerView.ViewHolder(b.root) {
        init { b.root.setOnClickListener { onClick(data[adapterPosition]) } }
    }

    override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
        VH(ItemThreadBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val t = data[i]
        h.b.tvTitle.text = t.title
        h.b.tvMeta.text = "${t.author} · ${t.dateText}"
    }
}
