package com.example.litreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ThreadEntity::class, ThreadContentEntity::class], version = 3)
abstract class AppDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao
    abstract fun threadContentDao(): ThreadContentDao

    companion object {
        /**
         * v2→v3：threads 增加 replies 列；sourceId 由旧的单一 "t66y" 归入文学区
         * "t66y_lit"（贴图区为 "t66y_img"）。收藏与缓存全部保留。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE threads ADD COLUMN replies TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE threads SET sourceId = 't66y_lit' WHERE sourceId = 't66y'")
            }
        }

        fun build(ctx: Context) = Room.databaseBuilder(ctx, AppDatabase::class.java, "lit.db")
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()
    }
}
