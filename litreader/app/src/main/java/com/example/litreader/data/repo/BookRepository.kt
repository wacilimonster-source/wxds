package com.example.litreader.data.repo

import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.model.Post
import com.example.litreader.data.source.SourceRegistry

class BookRepository(private val db: AppDatabase) {
    private val source = SourceRegistry.get("t66y")!!

    suspend fun loadList(page: Int, category: String = ""): List<ThreadEntity> {
        val items = source.getList(page, category)
        val ents = items.map {
            ThreadEntity(it.tid, source.id, it.title, it.author, it.timestamp, it.dateText, it.href, category)
        }
        db.threadDao().upsertAll(ents)
        return ents
    }

    suspend fun getThread(tid: String, onlyOp: Boolean): List<Post> = source.getThread(tid, onlyOp)

    suspend fun cached(sourceId: String = "t66y") = db.threadDao().all(sourceId)

    suspend fun search(q: String, sourceId: String = "t66y") = db.threadDao().search(sourceId, "%$q%")

    suspend fun crawlAll(category: String = "", pages: Int = 68) {
        for (p in 1..pages) loadList(p, category)
    }
}
