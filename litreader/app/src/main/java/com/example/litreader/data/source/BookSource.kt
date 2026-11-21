package com.example.litreader.data.source

import com.example.litreader.data.model.Post
import com.example.litreader.data.model.ThreadItem

/** 内容形态：决定列表分页机制与详情页打开方式。
 *  TEXT=本地目录同步（文学）；POSTS=站点页按需抓取、纯文字行（技术讨论）；
 *  IMAGE=站点页 + 缩略（达盖尔）；VIDEO=站点页 + 详情直接起播放器（在线影院）。 */
enum class SourceStyle { TEXT, POSTS, IMAGE, VIDEO }

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

/** 帖内一个可播放的视频条目：videoHost 页参数 + 展示标签（多段视频时用于切换）。 */
data class VideoLink(
    /** 承载播放器页的站点（如 yuese99.com），用于取真实 CDN 基址 */
    val host: String,
    /** 播放器页完整地址（video.html?id=..&time=..&sign=..） */
    val pageUrl: String,
    /** 页参数（页脚脚本里也有一份，页抓取失败时兜底） */
    val id: String,
    val time: String,
    val sign: String,
    /** 展示用标签（多段视频：「第 1 段」「第 2 段」） */
    val label: String
)

/** 影院类源的能力接口：从帖子 OP 提取视频页链接，并解析为可直接播放的流地址。 */
interface VideoSource {
    /** 提取 OP 里的全部视频（外链 iframe / 直链流）；无视频返回空表。 */
    suspend fun getVideos(tid: String): List<VideoLink>

    /** 解析为可播放地址（m3u8/mp4）；失败抛异常，由调用方提示重试。 */
    suspend fun resolveStream(link: VideoLink): String
}
