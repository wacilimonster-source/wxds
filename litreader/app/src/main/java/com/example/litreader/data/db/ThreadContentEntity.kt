package com.example.litreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "thread_content")
data class ThreadContentEntity(
    @PrimaryKey val tid: String,
    val onlyOp: Boolean,
    val html: String,
    val savedAt: Long
)
