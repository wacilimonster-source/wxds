package com.example.litreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ThreadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<ThreadEntity>)

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId ORDER BY timestamp DESC")
    suspend fun all(sourceId: String): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE sourceId = :sourceId AND title LIKE :q ORDER BY timestamp DESC")
    suspend fun search(sourceId: String, q: String): List<ThreadEntity>

    @Query("SELECT COUNT(*) FROM threads WHERE sourceId = :sourceId")
    suspend fun count(sourceId: String): Int
}
