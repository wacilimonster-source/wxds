package com.example.litreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val tid: String,
    val sourceId: String,
    val title: String,
    val author: String,
    val timestamp: Long,
    val dateText: String,
    val href: String,
    val category: String,
    val tag: String = "",
    val likes: String = "",
    val replies: String = "",
    /** 抓取自站点第几页（贴图区页码与站点对齐；文学区目录同步时同样记录） */
    val sitePage: Int = 0,
    val favorite: Boolean = false
)
