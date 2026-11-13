package com.example.litreader.data.source

import com.example.litreader.data.model.Post
import com.example.litreader.data.model.ThreadItem

/** 内容形态：决定详情页的阅读器样式。 */
enum class SourceStyle { TEXT, IMAGE }

/** 目录页拉取结果：条目 + 该区总页数（从页脚解析，解析不到为 0）。 */
data class CatalogPage(val items: List<ThreadItem>, val totalPages: Int)

/** 帖子详情一页：该页楼层 + 站点总页数（游客每页约 3 帖，必须按站点页继续拉）。 */
data class ThreadPage(val posts: List<Post>, val totalPages: Int)

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

    /** 按站点页拉取详情；默认实现只有一页，供非 t66y 源兼容。 */
    suspend fun getThreadPage(tid: String, onlyOp: Boolean, page: Int): ThreadPage =
        ThreadPage(getThread(tid, onlyOp), 1)
}
