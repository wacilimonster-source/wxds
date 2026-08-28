package com.example.litreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ThreadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<ThreadEntity>)

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND category = :category ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun page(sourceId: String, category: String, limit: Int, offset: Int): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND category = :category")
    suspend fun byCategory(sourceId: String, category: String): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND favorite = 1 ORDER BY timestamp DESC")
    suspend fun favorites(sourceId: String): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND title LIKE :q ORDER BY timestamp DESC")
    suspend fun search(sourceId: String, q: String): List<ThreadEntity>

    @Query("UPDATE threads SET favorite = :fav WHERE tid = :tid")
    suspend fun setFavorite(tid: String, fav: Boolean)

    @Query("SELECT favorite FROM threads WHERE tid = :tid")
    suspend fun isFavorite(tid: String): Boolean?

    @Query("SELECT COUNT(*) FROM threads WHERE sourceId = :sourceId AND category = :category")
    suspend fun countByCategory(sourceId: String, category: String): Int

    @Query("SELECT COUNT(*) FROM threads WHERE sourceId = :sourceId")
    suspend fun totalCount(sourceId: String): Int
}

@Dao
interface ThreadContentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: ThreadContentEntity)

    @Query("SELECT * FROM thread_content WHERE tid = :tid AND onlyOp = :onlyOp")
    suspend fun get(tid: String, onlyOp: Boolean): ThreadContentEntity?

    @Query("DELETE FROM thread_content WHERE savedAt < :before")
    suspend fun cleanOld(before: Long)
}
