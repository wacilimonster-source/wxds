package com.example.litreader.data.repo

import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.ThreadContentEntity
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.model.Post
import com.example.litreader.data.source.SourceRegistry

class BookRepository(private val db: AppDatabase) {

    private fun source(id: String) = SourceRegistry.get(id) ?: SourceRegistry.first()

    /** 在线拉取一页列表并入库（按当前分类存放）。 */
    suspend fun loadList(sourceId: String, page: Int, category: String = ""): List<ThreadEntity> {
        val src = source(sourceId)
        val items = src.getList(page, category)
        val ents = items.map {
            ThreadEntity(
                tid = it.tid,
                sourceId = src.id,
                title = it.title,
                author = it.author,
                timestamp = it.timestamp,
                dateText = it.dateText,
                href = it.href,
                category = category,
                tag = it.tag,
                likes = it.likes,
                replies = it.replies
            )
        }
        db.threadDao().upsertAll(ents)
        return ents
    }

    /** 本地分页（秒开）；条目不足时自动在线补页。 */
    suspend fun page(
        sourceId: String,
        page: Int,
        pageSize: Int,
        category: String,
        onlyFavorite: Boolean
    ): List<ThreadEntity> {
        val local = db.threadDao().page(sourceId, category, pageSize, (page - 1) * pageSize)
        if (onlyFavorite) return local
        if (local.size >= pageSize || page > 1) return local
        // 第一页且本地不足：预取几页做底量
        var acc = emptyList<ThreadEntity>()
        for (p in 1..PREFETCH_PAGES) {
            acc = acc + loadList(sourceId, p, category)
            if (acc.size >= pageSize) break
        }
        return if (acc.isNotEmpty()) db.threadDao().page(sourceId, category, pageSize, 0) else local
    }

    /** filter 为 null 时返回全部区收藏。 */
    suspend fun favorites(filter: String?): List<ThreadEntity> =
        if (filter == null) db.threadDao().favoritesAll()
        else db.threadDao().favorites(filter)

    suspend fun categoryCount(sourceId: String, category: String): Int =
        if (category.isEmpty()) db.threadDao().totalCount(sourceId)
        else db.threadDao().countByCategory(sourceId, category)

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
        const val PREFETCH_PAGES = 4
        const val PAGE_SIZE = 30
    }
}
