package com.example.litreader.ui

import android.content.Context
import android.content.Intent
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.ui.reader.ReaderActivity

/** 帖子入口统一导航：文学与贴图都进原生阅读器（内联图、点击进画廊、论坛模式齐备）。 */
object ThreadNav {
    fun open(ctx: Context, t: ThreadEntity) {
        ctx.startActivity(Intent(ctx, ReaderActivity::class.java).apply {
            putExtra("tid", t.tid)
            putExtra("title", t.title)
            putExtra("sourceId", t.sourceId)
        })
    }
}
