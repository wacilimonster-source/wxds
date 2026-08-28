package com.example.litreader.data.repo

import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.ThreadContentEntity
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.model.Post
import com.example.litreader.data.source.SourceRegistry

class BookRepository(private val db: AppDatabase) {
    private val source = SourceRegistry.get("t66y")!!

    /** 在线拉取一页列表并入库（按当前分类存放）。 */
    suspend fun loadList(page: Int, category: String = ""): List<ThreadEntity> {
        val items = source.getList(page, category)
        val ents = items.map {
            ThreadEntity(
                tid = it.tid,
                sourceId = source.id,
                title = it.title,
                author = it.author,
                timestamp = it.timestamp,
                dateText = it.dateText,
                href = it.href,
                category = category,
                tag = it.tag,
                likes = it.likes
            )
        }
        db.threadDao().upsertAll(ents)
        return ents
    }

    /** 本地分页（秒开）；条目不足时自动在线补页。 */
    suspend fun page(page: Int, pageSize: Int, category: String, onlyFavorite: Boolean): List<ThreadEntity> {
        val local = db.threadDao().page(source.id, category, pageSize, (page - 1) * pageSize)
        if (onlyFavorite) return local
        if (local.size >= pageSize || page > 1) return local
        // 第一页且本地不足：预取几页做底量
        var fetched = 0
        var acc = emptyList<ThreadEntity>()
        for (p in 1..PREFETCH_PAGES) {
            acc = acc + loadList(p, category)
            if (acc.size >= pageSize) break
        }
        fetched = acc.size
        return if (fetched > 0) db.threadDao().page(source.id, category, pageSize, 0) else local
    }

    suspend fun favorites(): List<ThreadEntity> = db.threadDao().favorites(source.id)

    suspend fun categoryCount(category: String): Int =
        if (category.isEmpty()) db.threadDao().totalCount(source.id)
        else db.threadDao().countByCategory(source.id, category)

    suspend fun search(q: String): List<ThreadEntity> =
        if (q.isBlank()) db.threadDao().favorites(source.id) else db.threadDao().search(source.id, "%$q%")

    suspend fun setFavorite(tid: String, fav: Boolean) = db.threadDao().setFavorite(tid, fav)

    suspend fun isFavorite(tid: String): Boolean = db.threadDao().isFavorite(tid) ?: false

    /** 正文：先读缓存（离线可看），没有才在线抓并写缓存。 */
    suspend fun getThread(tid: String, onlyOp: Boolean): Pair<List<Post>, Boolean> {
        val cached = db.threadContentDao().get(tid, onlyOp)
        if (cached != null) {
            val cachedPosts = runCatching { decodePosts(cached.html) }.getOrNull()
            if (cachedPosts != null) return cachedPosts to true
        }
        val posts = source.getThread(tid, onlyOp)
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
