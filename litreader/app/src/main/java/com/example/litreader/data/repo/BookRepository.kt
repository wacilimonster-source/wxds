package com.example.litreader.data.repo

import android.content.SharedPreferences
import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.TagCount
import com.example.litreader.data.db.ThreadContentEntity
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.model.Post
import com.example.litreader.data.model.ThreadItem
import com.example.litreader.data.source.CatalogPage
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.SourceStyle
import kotlin.math.ceil

class BookRepository(
    private val db: AppDatabase,
    private val prefs: SharedPreferences? = null
) {

    private fun source(id: String) = SourceRegistry.get(id) ?: SourceRegistry.first()

    private fun toEntity(sourceId: String, it: ThreadItem): ThreadEntity = ThreadEntity(
        tid = it.tid,
        sourceId = sourceId,
        title = it.title,
        author = it.author,
        timestamp = it.timestamp,
        dateText = it.dateText,
        href = it.href,
        category = it.category,
        tag = it.tag,
        likes = it.likes,
        replies = it.replies
    )

    /** 目录页落库：盖 sitePage 章 + 保护已有收藏标记（否则 REPLACE 会重置收藏）。 */
    private suspend fun upsertItems(sourceId: String, page: Int, items: List<ThreadItem>): List<ThreadEntity> {
        if (items.isEmpty()) return emptyList()
        val favSet = db.threadDao().favoriteTids(items.map { it.tid }).toSet()
        val ents = items.map {
            toEntity(sourceId, it).copy(sitePage = page, favorite = it.tid in favSet)
        }
        db.threadDao().upsertAll(ents)
        return ents
    }

    private fun rememberSitePages(sourceId: String, totalPages: Int) {
        if (totalPages > 0) prefs?.edit()?.putInt("site_pages_$sourceId", totalPages)?.apply()
    }

    /** 站点总页数（页脚解析值，抓取时刷新）。 */
    fun sitePageCount(sourceId: String): Int = prefs?.getInt("site_pages_$sourceId", 0) ?: 0

    /** 在线拉取一页列表并入库。 */
    suspend fun loadList(sourceId: String, page: Int, category: String = ""): List<ThreadEntity> {
        val src = source(sourceId)
        val cp = src.getCatalogPage(page, category)
        rememberSitePages(sourceId, cp.totalPages)
        return upsertItems(src.id, page, cp.items)
    }

    /** 文学区本地分页（30 条/页，目录由 syncCatalog 全量落库）。tag 为空串表示不过滤。 */
    suspend fun page(
        sourceId: String,
        page: Int,
        pageSize: Int,
        category: String,
        tag: String = ""
    ): List<ThreadEntity> =
        db.threadDao().page(sourceId, category, tag, pageSize, (page - 1) * pageSize)

    /**
     * 贴图区分页：页码与站点对齐（100 条/页）。
     * 本地没有该页 → 实时抓取落库；看过的页秒开、离线可回看。
     * forceRefresh 时先删该页非收藏行再重抓（刷新语义）。
     */
    suspend fun galleryPage(sourceId: String, page: Int, forceRefresh: Boolean = false): List<ThreadEntity> {
        if (forceRefresh) db.threadDao().deleteBySitePage(sourceId, page)
        val cached = db.threadDao().bySitePage(sourceId, page)
        if (cached.isNotEmpty()) return cached
        return loadList(sourceId, page, "")
    }

    /** 目录同步进度。 */
    data class SyncProgress(val page: Int, val totalPages: Int, val totalNew: Int)

    /**
     * 文学区目录同步（只抓列表元数据，不含正文）：
     * - 未完成过全量：逐页拉到最后一页，成功后打 crawled 标记（中断后下次自动重来）
     * - 已全量过：从第 1 页往下增量，新帖只会出现在顶部，遇到整页已知帖即停
     * 每页照常 upsert，顺带刷新已知帖的赞数/回复/最后回帖时间。
     */
    suspend fun syncCatalog(sourceId: String, onProgress: suspend (SyncProgress) -> Unit) {
        val src = source(sourceId)
        val crawledKey = "catalog_crawled_$sourceId"
        cleanupLegacyTags(sourceId)
        val fullCrawl = prefs?.getBoolean(crawledKey, false) != true
        var totalNew = 0
        var page = 1
        var totalPages = 0
        while (page <= MAX_SYNC_PAGES) {
            val cp: CatalogPage = src.getCatalogPage(page, "")
            if (cp.totalPages > totalPages) totalPages = cp.totalPages
            rememberSitePages(sourceId, cp.totalPages)
            val tids = cp.items.map { it.tid }
            val known = if (tids.isEmpty()) emptySet() else db.threadDao().existingTids(tids).toSet()
            val newOnPage = cp.items.count { it.tid !in known }
            totalNew += newOnPage
            upsertItems(src.id, page, cp.items)
            onProgress(SyncProgress(page, totalPages, totalNew))
            if (!fullCrawl && newOnPage == 0) break
            if (totalPages in 1..page) break
            page++
        }
        prefs?.edit()?.putBoolean(crawledKey, true)?.apply()
    }

    /** 0.6 前 tag 存的是 "[現代奇幻]" 带括注原文（含积分噪音），一次性清洗为括注内文本。 */
    private suspend fun cleanupLegacyTags(sourceId: String) {
        if (prefs?.getBoolean("tag_cleanup_v06_$sourceId", false) == true) return
        val rows = db.threadDao().all().filter { it.sourceId == sourceId }
        val fixed = rows.mapNotNull { row ->
            val cleaned = Regex("""\[([^\[\]]+)""").find(row.tag)?.groupValues?.get(1)?.trim() ?: ""
            if (cleaned != row.tag) row.copy(tag = cleaned) else null
        }
        if (fixed.isNotEmpty()) db.threadDao().upsertAll(fixed)
        prefs?.edit()?.putBoolean("tag_cleanup_v06_$sourceId", true)?.apply()
    }

    /** 各标签及数量（按数量降序，已剔除空标签）。 */
    suspend fun tagCounts(sourceId: String): List<TagCount> =
        db.threadDao().tagCounts(sourceId)

    /** filter 为 null 时返回全部区收藏。 */
    suspend fun favorites(filter: String?): List<ThreadEntity> =
        if (filter == null) db.threadDao().favoritesAll()
        else db.threadDao().favorites(filter)

    suspend fun categoryCount(sourceId: String, category: String, tag: String = ""): Int =
        if (category.isEmpty() && tag.isEmpty()) db.threadDao().totalCount(sourceId)
        else db.threadDao().countByCategory(sourceId, category, tag)

    /** 跨区本地搜索（搜的是已缓存进库的标题）。 */
    suspend fun search(q: String): List<ThreadEntity> =
        db.threadDao().searchAll("%$q%")

    suspend fun setFavorite(tid: String, fav: Boolean) = db.threadDao().setFavorite(tid, fav)

    suspend fun isFavorite(tid: String): Boolean = db.threadDao().isFavorite(tid) ?: false

    /** 帖子属于哪个区（详情页兜底用）。 */
    suspend fun threadSourceId(tid: String): String? = db.threadDao().byId(tid)?.sourceId

    /** 正文：先读缓存（离线可看），没有才在线抓并写缓存。 */
    suspend fun getThread(sourceId: String, tid: String, onlyOp: Boolean): Pair<List<Post>, Boolean> {
        val cached = db.threadContentDao().get(tid, onlyOp)
        if (cached != null) {
            val cachedPosts = runCatching { decodePosts(cached.html) }.getOrNull()
            if (cachedPosts != null) return cachedPosts to true
        }
        val posts = source(sourceId).getThread(tid, onlyOp)
        db.threadContentDao().upsert(ThreadContentEntity(tid, onlyOp, encodePosts(posts), System.currentTimeMillis()))
        return posts to false
    }

    private fun encodePosts(posts: List<Post>): String = org.json.JSONArray().apply {
        posts.forEach { p ->
            put(
                org.json.JSONObject().apply {
                    put("a", p.author)
                    put("c", p.contentHtml)
                    put("f", p.floor)
                    put("d", p.dateText)
                }
            )
        }
    }.toString()

    private fun decodePosts(json: String): List<Post> {
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Post(
                author = o.optString("a"),
                contentHtml = o.getString("c"),
                floor = o.optInt("f", 0),
                dateText = o.optString("d")
            )
        }
    }

    companion object {
        const val PAGE_SIZE = 30
        /** 目录全量同步的页数上限（fid=20 当前 68 页，留足余量） */
        const val MAX_SYNC_PAGES = 300
    }
}
