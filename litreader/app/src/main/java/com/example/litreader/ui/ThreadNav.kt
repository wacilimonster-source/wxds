package com.example.litreader.ui

import android.content.Context
import android.content.Intent
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.SourceStyle
import com.example.litreader.ui.player.PlayerActivity
import com.example.litreader.ui.reader.ReaderActivity

/** 帖子入口统一导航：影院区直接进播放器，文学/贴图/技术进原生阅读器（内联图、点击进画廊、论坛模式齐备）。 */
object ThreadNav {
    fun open(ctx: Context, t: ThreadEntity) {
        val target = if (SourceRegistry.get(t.sourceId)?.style == SourceStyle.VIDEO) {
            PlayerActivity::class.java
        } else {
            ReaderActivity::class.java
        }
        ctx.startActivity(Intent(ctx, target).apply {
            putExtra("tid", t.tid)
            putExtra("title", t.title)
            putExtra("sourceId", t.sourceId)
        })
    }
}
