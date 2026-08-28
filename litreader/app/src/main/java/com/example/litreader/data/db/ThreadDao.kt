package com.example.litreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ThreadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<ThreadEntity>)

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND category = :category AND (:tag = '' OR tag = :tag) ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun page(sourceId: String, category: String, tag: String, limit: Int, offset: Int): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND category = :category")
    suspend fun byCategory(sourceId: String, category: String): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND favorite = 1 ORDER BY timestamp DESC")
    suspend fun favorites(sourceId: String): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE favorite = 1 ORDER BY timestamp DESC")
    suspend fun favoritesAll(): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND title LIKE :q ORDER BY timestamp DESC")
    suspend fun search(sourceId: String, q: String): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE title LIKE :q ORDER BY timestamp DESC")
    suspend fun searchAll(q: String): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE tid = :tid")
    suspend fun byId(tid: String): ThreadEntity?

    @Query("SELECT tid FROM threads WHERE tid IN (:tids)")
    suspend fun existingTids(tids: List<String>): List<String>

    @Query("SELECT tid FROM threads WHERE favorite = 1 AND tid IN (:tids)")
    suspend fun favoriteTids(tids: List<String>): List<String>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND sitePage = :sitePage ORDER BY timestamp DESC")
    suspend fun bySitePage(sourceId: String, sitePage: Int): List<ThreadEntity>

    @Query("DELETE FROM threads WHERE sourceId = :sourceId AND sitePage = :sitePage AND favorite = 0")
    suspend fun deleteBySitePage(sourceId: String, sitePage: Int)

    @Query("SELECT * FROM threads")
    suspend fun all(): List<ThreadEntity>

    @Query("SELECT tag, COUNT(*) AS n FROM threads WHERE sourceId = :sourceId AND tag != '' GROUP BY tag ORDER BY n DESC")
    suspend fun tagCounts(sourceId: String): List<TagCount>

    @Query("UPDATE threads SET favorite = :fav WHERE tid = :tid")
    suspend fun setFavorite(tid: String, fav: Boolean)

    @Query("SELECT favorite FROM threads WHERE tid = :tid")
    suspend fun isFavorite(tid: String): Boolean?

    @Query("SELECT COUNT(*) FROM threads WHERE sourceId = :sourceId AND category = :category AND (:tag = '' OR tag = :tag)")
    suspend fun countByCategory(sourceId: String, category: String, tag: String): Int

    @Query("SELECT COUNT(*) FROM threads WHERE sourceId = :sourceId")
    suspend fun totalCount(sourceId: String): Int
}

/** 标签聚合结果（Room POJO）。 */
data class TagCount(val tag: String, val n: Int)

@Dao
interface ThreadContentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: ThreadContentEntity)

    @Query("SELECT * FROM thread_content WHERE tid = :tid AND onlyOp = :onlyOp")
    suspend fun get(tid: String, onlyOp: Boolean): ThreadContentEntity?

    @Query("DELETE FROM thread_content WHERE savedAt < :before")
    suspend fun cleanOld(before: Long)
}
