package com.example.litreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ThreadEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao

    companion object {
        fun build(ctx: Context) = Room.databaseBuilder(ctx, AppDatabase::class.java, "lit.db")
            .fallbackToDestructiveMigration()
            .build()
    }
}
