package com.example.litreader.data.model

data class Post(
    val author: String,
    val contentHtml: String,
    val floor: Int,
    val dateText: String = "",
    val replies: Int = 0,
    val likes: String = ""
)
