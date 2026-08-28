package com.example.litreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ThreadEntity::class, ThreadContentEntity::class], version = 4)
abstract class AppDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao
    abstract fun threadContentDao(): ThreadContentDao

    companion object {
        /**
         * v2→v3：threads 增加 replies 列；sourceId 由旧的单一 "t66y" 归入文学区
         * "t66y_lit"（贴图区为 "t66y_img"）。收藏与缓存全部保留。
         * v3→v4：增加 sitePage 列（贴图区页码与站点对齐、按需抓取）。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE threads ADD COLUMN replies TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE threads SET sourceId = 't66y_lit' WHERE sourceId = 't66y'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE threads ADD COLUMN sitePage INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(ctx: Context) = Room.databaseBuilder(ctx, AppDatabase::class.java, "lit.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()
    }
}
