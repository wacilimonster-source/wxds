package com.example.litreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ThreadEntity::class, ThreadContentEntity::class], version = 9)
abstract class AppDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao
    abstract fun threadContentDao(): ThreadContentDao

    companion object {
        /**
         * v2→v3：threads 增加 replies 列；sourceId 由旧的单一 "t66y" 归入文学区
         * "t66y_lit"（贴图区为 "t66y_img"）。收藏与缓存全部保留。
         * v3→v4：增加 sitePage 列（贴图区页码与站点对齐、按需抓取）。
         * v4→v5：增加 readState 列（0=未读 1=已读 2=已读完）。
         * v5→v6：增加 favoriteAt 列（收藏先后排序）。
         * v6→v7：增加 lastOpenAt 列（书架「最近浏览」排序，0=未浏览过）。
         * v7→v8：threads 表补索引（列表分页/收藏/最近浏览/贴图按页缓存此前全表扫描）。
         * v8→v9：threads 增加 thumb 列（列表行内懒加载首图，贴图区缩略卡）。
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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE threads ADD COLUMN readState INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE threads ADD COLUMN favoriteAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE threads ADD COLUMN lastOpenAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_threads_sourceId_category_timestamp` ON threads (`sourceId`, `category`, `timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_threads_favorite` ON threads (`favorite`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_threads_lastOpenAt` ON threads (`lastOpenAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_threads_sitePage` ON threads (`sitePage`)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE threads ADD COLUMN thumb TEXT NOT NULL DEFAULT ''")
            }
        }

        fun build(ctx: Context) = Room.databaseBuilder(ctx, AppDatabase::class.java, "lit.db")
            .addMigrations(
                MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
            )
            .fallbackToDestructiveMigration()
            .build()
    }
}
