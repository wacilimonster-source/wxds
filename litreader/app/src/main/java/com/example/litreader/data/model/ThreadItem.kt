package com.example.litreader.data.model

data class ThreadItem(
    val tid: String,
    val title: String,
    val author: String,
    val timestamp: Long,
    val dateText: String,
    val href: String,
    val category: String = "",
    val tag: String = "",
    val likes: String = "",
    val replies: String = "",
    /** 列表行内懒加载首图地址（贴图区缩略卡用；无图为空串） */
    val thumb: String = ""
)
