package com.example.litreader.data.source

import com.example.litreader.data.model.Post
import com.example.litreader.data.model.ThreadItem

/** 内容形态：决定详情页的阅读器样式。 */
enum class SourceStyle { TEXT, IMAGE }

/** 目录页拉取结果：条目 + 该区总页数（从页脚解析，解析不到为 0）。 */
data class CatalogPage(val items: List<ThreadItem>, val totalPages: Int)

interface BookSource {
    val id: String
    val name: String
    /** 底部导航/收藏筛选等处用的短名 */
    val shortName: String
    val fid: String
    val categories: List<Pair<String, String>>
    /** 站点列表每页条数（fid=20 与 fid=16 均为 100） */
    val remotePageSize: Int
    val style: SourceStyle
    suspend fun getList(page: Int, category: String = ""): List<ThreadItem>
    suspend fun getCatalogPage(page: Int, category: String = ""): CatalogPage =
        CatalogPage(getList(page, category), 0)
    suspend fun getThread(tid: String, onlyOp: Boolean): List<Post>
}
