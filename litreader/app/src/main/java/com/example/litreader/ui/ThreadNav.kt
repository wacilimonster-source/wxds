package com.example.litreader.ui

import android.content.Context
import android.content.Intent
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.SourceStyle
import com.example.litreader.ui.detail.ThreadDetailActivity
import com.example.litreader.ui.player.PlayerActivity
import com.example.litreader.ui.reader.ReaderActivity

/**
 * 帖子入口统一导航：影院→播放器；技术/贴图→论坛楼层视图（默认只看楼主，可切全部，
 * 观感是「帖子」而不是播放器式沉浸翻页）；文学→原生阅读器。
 */
object ThreadNav {
    fun open(ctx: Context, t: ThreadEntity) {
        val target = when (SourceRegistry.get(t.sourceId)?.style) {
            SourceStyle.VIDEO -> PlayerActivity::class.java
            SourceStyle.POSTS, SourceStyle.IMAGE -> ThreadDetailActivity::class.java
            else -> ReaderActivity::class.java
        }
        ctx.startActivity(Intent(ctx, target).apply {
            putExtra("tid", t.tid)
            putExtra("title", t.title)
            putExtra("sourceId", t.sourceId)
        })
    }
}
