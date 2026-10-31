package com.example.litreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ThreadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<ThreadEntity>)

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND category = :category AND (:tag = '' OR tag = :tag) AND (:readFilter = -1 OR readState = :readFilter) ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun page(sourceId: String, category: String, tag: String, readFilter: Int, limit: Int, offset: Int): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND category = :category")
    suspend fun byCategory(sourceId: String, category: String): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND favorite = 1 ORDER BY timestamp DESC")
    suspend fun favorites(sourceId: String): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE favorite = 1 ORDER BY timestamp DESC")
    suspend fun favoritesAll(): List<ThreadEntity>

    /** 最近浏览（收藏的不重复出现），按打开时间倒序，条数由 repo 层上限控制。 */
    @Query("SELECT * FROM threads WHERE lastOpenAt > 0 AND favorite = 0 ORDER BY lastOpenAt DESC LIMIT :limit")
    suspend fun recentsAll(limit: Int): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE lastOpenAt > 0 AND favorite = 0 AND sourceId = :sourceId ORDER BY lastOpenAt DESC LIMIT :limit")
    suspend fun recents(sourceId: String, limit: Int): List<ThreadEntity>

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

    @Query("SELECT tid, favorite, readState, favoriteAt, lastOpenAt FROM threads WHERE tid IN (:tids)")
    suspend fun existingMeta(tids: List<String>): List<TidMeta>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND sitePage = :sitePage ORDER BY timestamp DESC")
    suspend fun bySitePage(sourceId: String, sitePage: Int): List<ThreadEntity>

    @Query("DELETE FROM threads WHERE sourceId = :sourceId AND sitePage = :sitePage AND favorite = 0")
    suspend fun deleteBySitePage(sourceId: String, sitePage: Int)

    @Query("DELETE FROM threads WHERE sourceId = :sourceId AND sitePage = :sitePage AND favorite = 0 AND tid NOT IN (:tids)")
    suspend fun deleteStaleBySitePage(sourceId: String, sitePage: Int, tids: List<String>)

    @Query("UPDATE threads SET readState = 1 WHERE tid = :tid AND readState = 0")
    suspend fun markRead(tid: String)

    /** 打开帖子后从正文缓存提取的首图（贴图列表缩略卡用）。 */
    @Query("UPDATE threads SET thumb = :thumb WHERE tid = :tid AND thumb = ''")
    suspend fun updateThumb(tid: String, thumb: String)

    @Query("UPDATE threads SET readState = 2 WHERE tid = :tid")
    suspend fun markFinished(tid: String)

    @Query("SELECT * FROM threads")
    suspend fun all(): List<ThreadEntity>

    @Query("SELECT tag, COUNT(*) AS n FROM threads WHERE sourceId = :sourceId AND tag != '' GROUP BY tag ORDER BY n DESC")
    suspend fun tagCounts(sourceId: String): List<TagCount>

    @Query("UPDATE threads SET favorite = :fav, favoriteAt = :now WHERE tid = :tid")
    suspend fun setFavorite(tid: String, fav: Boolean, now: Long)

    @Query("UPDATE threads SET lastOpenAt = :now WHERE tid = :tid")
    suspend fun markOpened(tid: String, now: Long)

    /** 只保留最近 :keep 条浏览记录，更旧的把 lastOpenAt 置 0（不动帖子数据）。 */
    @Query("UPDATE threads SET lastOpenAt = 0 WHERE lastOpenAt > 0 AND tid NOT IN (SELECT tid FROM threads WHERE lastOpenAt > 0 ORDER BY lastOpenAt DESC LIMIT :keep)")
    suspend fun trimRecents(keep: Int)

    @Query("UPDATE threads SET lastOpenAt = 0 WHERE lastOpenAt > 0")
    suspend fun clearRecents()

    /** 撤销清空：按快照成对恢复（Room 不支持批量键值对，逐条执行）。 */
    suspend fun restoreLastOpenAt(snapshot: List<Pair<String, Long>>) {
        snapshot.forEach { (tid, ts) -> restoreOne(tid, ts) }
    }

    @Query("UPDATE threads SET lastOpenAt = :ts WHERE tid = :tid")
    suspend fun restoreOne(tid: String, ts: Long)

    @Query("SELECT favorite FROM threads WHERE tid = :tid")
    suspend fun isFavorite(tid: String): Boolean?

    @Query("SELECT COUNT(*) FROM threads WHERE sourceId = :sourceId AND category = :category AND (:tag = '' OR tag = :tag) AND (:readFilter = -1 OR readState = :readFilter)")
    suspend fun countByCategory(sourceId: String, category: String, tag: String, readFilter: Int): Int

    @Query("SELECT COUNT(*) FROM threads WHERE sourceId = :sourceId")
    suspend fun totalCount(sourceId: String): Int
}

/** 标签聚合结果（Room POJO）。 */
data class TagCount(val tag: String, val n: Int)

/** 帖子标记回读（upsert 前保留收藏/已读/浏览时间，Room POJO）。 */
data class TidMeta(val tid: String, val favorite: Boolean, val readState: Int, val favoriteAt: Long, val lastOpenAt: Long)

@Dao
interface ThreadContentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: ThreadContentEntity)

    @Query("SELECT * FROM thread_content WHERE tid = :tid AND onlyOp = :onlyOp")
    suspend fun get(tid: String, onlyOp: Boolean): ThreadContentEntity?

    @Query("DELETE FROM thread_content WHERE savedAt < :before")
    suspend fun cleanOld(before: Long)
}
