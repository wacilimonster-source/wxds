package com.example.litreader.ui

import android.content.Context
import android.content.Intent
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.SourceStyle
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.ui.reader.ReaderActivity

/** 帖子入口统一导航：文学区默认进阅读器，贴图区进论坛详情。 */
object ThreadNav {
    fun open(ctx: Context, t: ThreadEntity) {
        val style = SourceRegistry.get(t.sourceId)?.style ?: SourceStyle.TEXT
        if (style == SourceStyle.TEXT) {
            ctx.startActivity(Intent(ctx, ReaderActivity::class.java).apply {
                putExtra("tid", t.tid)
                putExtra("title", t.title)
                putExtra("sourceId", t.sourceId)
            })
        } else {
            ctx.startActivity(Intent(ctx, ThreadDetailActivity::class.java).apply {
                putExtra("tid", t.tid)
                putExtra("title", t.title)
                putExtra("favorite", t.favorite)
                putExtra("sourceId", t.sourceId)
            })
        }
    }
}
