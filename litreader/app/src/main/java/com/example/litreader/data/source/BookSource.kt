package com.example.litreader.data.source

import com.example.litreader.data.model.Post
import com.example.litreader.data.model.ThreadItem

interface BookSource {
    val id: String
    val name: String
    val categories: List<Pair<String, String>>
    suspend fun getList(page: Int, category: String = ""): List<ThreadItem>
    suspend fun getThread(tid: String, onlyOp: Boolean): List<Post>
}
