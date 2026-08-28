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
    val favorite: Boolean = false
)
