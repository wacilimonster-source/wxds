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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    /** 目录页落库：盖 sitePage 章 + 保护已有收藏/已读/浏览时间（否则 REPLACE 会重置）。 */
    private suspend fun upsertItems(sourceId: String, page: Int, items: List<ThreadItem>): List<ThreadEntity> {
        if (items.isEmpty()) return emptyList()
        val meta = db.threadDao().existingMeta(items.map { it.tid }).associateBy { it.tid }
        val ents = items.map {
            val m = meta[it.tid]
            toEntity(sourceId, it).copy(
                sitePage = page,
                favorite = m?.favorite ?: false,
                readState = m?.readState ?: 0,
                favoriteAt = m?.favoriteAt ?: 0,
                lastOpenAt = m?.lastOpenAt ?: 0
            )
        }
        db.threadDao().upsertAll(ents)
        return ents
    }

    private fun rememberSitePages(sourceId: String, totalPages: Int) {
        if (totalPages > 0) prefs?.edit()?.putInt("site_pages_$sourceId", totalPages)?.apply()
    }

    /** 站点总页数（页脚解析值，抓取时刷新）。 */
    fun sitePageCount(sourceId: String): Int = prefs?.getInt("site_pages_$sourceId", 0) ?: 0

    /** 实测有效页深（页脚值虚高：站点归档早期帖子，深页返回空壳）。0=未探测。 */
    fun validPageCount(sourceId: String): Int = prefs?.getInt("valid_pages_$sourceId", 0) ?: 0

    fun saveValidPages(sourceId: String, pages: Int) {
        if (pages > 0) prefs?.edit()?.putInt("valid_pages_$sourceId", pages)?.apply()
    }

    /**
     * 二分探测最大有效页（low 已知有效、high 已知无效，不含端点）。
     * 每页走 loadList（带重试），顺带把有效页缓存入库。返回最大有效页。
     */
    suspend fun probeValidPages(sourceId: String, lowValid: Int, highInvalid: Int): Int {
        var lo = maxOf(1, lowValid)
        var hi = highInvalid
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            var rows = loadList(sourceId, mid, "")
            if (rows.isEmpty()) rows = loadList(sourceId, mid, "")  // 防瞬时空壳：再抓一次确认
            if (rows.isNotEmpty()) lo = mid else hi = mid
        }
        return lo
    }

    /** 目录单页抓取重试：站点时通时断，瞬时抖动重试 2 次再算失败。 */
    private suspend fun <T> withRetry(times: Int = 3, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (attempt < times - 1) delay(1000L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("retry failed")
    }

    /** 在线拉取一页列表并入库。 */
    suspend fun loadList(sourceId: String, page: Int, category: String = ""): List<ThreadEntity> {
        val src = source(sourceId)
        val cp = withRetry { src.getCatalogPage(page, category) }
        rememberSitePages(sourceId, cp.totalPages)
        return upsertItems(src.id, page, cp.items)
    }

    /** 文学区本地分页（30 条/页，目录由 syncCatalog 全量落库）。
     *  tag 空串=不过滤；readFilter -1=全部 0=未读 1=已读 2=已读完。 */
    suspend fun page(
        sourceId: String,
        page: Int,
        pageSize: Int,
        category: String,
        tag: String = "",
        readFilter: Int = -1
    ): List<ThreadEntity> =
        db.threadDao().page(sourceId, category, tag, readFilter, pageSize, (page - 1) * pageSize)

    /**
     * 贴图区分页：页码与站点对齐（100 条/页）。
     * 本地没有该页 → 实时抓取落库；看过的页秒开、离线可回看。
     * forceRefresh 时重抓该页（upsert 自动保留收藏/已读），并清理已从站点消失的非收藏残留。
     * 本地还不知道站点总页数时也强制抓一次（自愈：否则缓存页永远拿不到页脚总页数）。
     */
    suspend fun galleryPage(sourceId: String, page: Int, forceRefresh: Boolean = false): List<ThreadEntity> {
        if (forceRefresh || sitePageCount(sourceId) <= 0) {
            val fresh = loadList(sourceId, page, "")
            db.threadDao().deleteStaleBySitePage(sourceId, page, fresh.map { it.tid })
            return db.threadDao().bySitePage(sourceId, page)
        }
        val cached = db.threadDao().bySitePage(sourceId, page)
        if (cached.isNotEmpty()) return cached
        return loadList(sourceId, page, "")
    }

    /** 目录同步进度。 */
    data class SyncProgress(val page: Int, val totalPages: Int, val totalNew: Int)

    /**
     * 文学区目录同步（只抓列表元数据，不含正文）：
     * - 未完成过全量：3 页一批并发抓取、按序入库（首次全量约 1 分钟，串行需 3-4 分钟）
     * - 已全量过：从第 1 页往下增量，新帖只会出现在顶部，遇到整页已知帖即停
     * 每页照常 upsert，顺带刷新已知帖的赞数/回复/最后回帖时间。
     */
    suspend fun syncCatalog(sourceId: String, onProgress: suspend (SyncProgress) -> Unit) {
        val src = source(sourceId)
        val crawledKey = "catalog_crawled_$sourceId"
        cleanupLegacyTags(sourceId)
        val fullCrawl = prefs?.getBoolean(crawledKey, false) != true

        suspend fun upsertPage(page: Int, items: List<ThreadItem>): Int {
            val tids = items.map { it.tid }
            val known = if (tids.isEmpty()) emptySet() else db.threadDao().existingTids(tids).toSet()
            val newOnPage = items.count { it.tid !in known }
            upsertItems(src.id, page, items)
            return newOnPage
        }

        // 第 1 页先行：解析总页数（页脚），增量模式整页已知即完成
        val first = withRetry { src.getCatalogPage(1, "") }
        var totalPages = first.totalPages
        rememberSitePages(sourceId, totalPages)
        var totalNew = upsertPage(1, first.items)
        onProgress(SyncProgress(1, totalPages, totalNew))
        val lastPage = minOf(MAX_SYNC_PAGES, if (totalPages > 0) totalPages else MAX_SYNC_PAGES)

        if (!fullCrawl) {
            // 增量：新帖只在顶部，逐页往下直到整页已知
            var page = 2
            while (page <= lastPage) {
                val cp = withRetry { src.getCatalogPage(page, "") }
                if (cp.totalPages > totalPages) totalPages = cp.totalPages
                rememberSitePages(sourceId, cp.totalPages)
                val newOnPage = upsertPage(page, cp.items)
                totalNew += newOnPage
                onProgress(SyncProgress(page, totalPages, totalNew))
                if (newOnPage == 0) break
                page++
            }
            prefs?.edit()?.putBoolean(crawledKey, true)?.apply()
            return
        }

        // 全量：3 页一批并发抓取，按序入库保证进度顺序
        var page = 2
        while (page <= lastPage) {
            val batch = (page..minOf(page + SYNC_CONCURRENCY - 1, lastPage)).toList()
            val fetched = coroutineScope {
                batch.map { p -> async { p to withRetry { src.getCatalogPage(p, "") } } }.awaitAll()
            }.sortedBy { it.first }
            for ((p, cp) in fetched) {
                if (cp.totalPages > totalPages) totalPages = cp.totalPages
                rememberSitePages(sourceId, cp.totalPages)
                totalNew += upsertPage(p, cp.items)
                onProgress(SyncProgress(p, totalPages, totalNew))
            }
            page += batch.size
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

    /** 最近浏览（收藏的不重复出现）；filter 为 null 时跨区。 */
    suspend fun recents(filter: String?): List<ThreadEntity> =
        if (filter == null) db.threadDao().recentsAll(RECENT_KEEP)
        else db.threadDao().recents(filter, RECENT_KEEP)

    /** 打开帖子即记浏览时间，并把超出上限的旧记录挤出「最近」。 */
    suspend fun markOpened(tid: String) {
        db.threadDao().markOpened(tid, System.currentTimeMillis())
        db.threadDao().trimRecents(RECENT_KEEP)
    }

    /** 清空最近浏览（只清时间戳，不动帖子数据）。 */
    suspend fun clearRecents() = db.threadDao().clearRecents()

    suspend fun categoryCount(sourceId: String, category: String, tag: String = "", readFilter: Int = -1): Int =
        if (category.isEmpty() && tag.isEmpty() && readFilter == -1) db.threadDao().totalCount(sourceId)
        else db.threadDao().countByCategory(sourceId, category, tag, readFilter)

    /** 打开过即记为已读（不改已读完标记）。 */
    suspend fun markRead(tid: String) = db.threadDao().markRead(tid)

    /** 已读完 / 取消已读完（取消后回到已读）。 */
    suspend fun setFinished(tid: String, finished: Boolean) {
        if (finished) db.threadDao().markFinished(tid) else db.threadDao().markRead(tid)
    }

    suspend fun readState(tid: String): Int = db.threadDao().byId(tid)?.readState ?: 0

    /** 本地标题搜索（已入库缓存）；filter 为 null 时跨区，否则限单区。 */
    suspend fun search(q: String, filter: String?): List<ThreadEntity> =
        if (filter == null) db.threadDao().searchAll("%$q%")
        else db.threadDao().search(filter, "%$q%")

    suspend fun setFavorite(tid: String, fav: Boolean) =
        db.threadDao().setFavorite(tid, fav, System.currentTimeMillis())

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
        /** 全量同步的并发抓取页数 */
        const val SYNC_CONCURRENCY = 3
        /** 「最近浏览」保留条数上限 */
        const val RECENT_KEEP = 100
    }
}
