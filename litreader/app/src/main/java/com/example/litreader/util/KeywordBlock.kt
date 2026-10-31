package com.example.litreader.util

import android.content.SharedPreferences

/**
 * 关键词屏蔽（文学/贴图分区各自一份）：标题命中任一关键词的帖子在列表默认不展示。
 * 关键词以 \u0001 分隔存 prefs（同搜索历史惯例），按 sourceId 区分。
 */
object KeywordBlock {
    private fun key(sourceId: String) = "block_keywords_$sourceId"

    fun list(prefs: SharedPreferences, sourceId: String): List<String> =
        prefs.getString(key(sourceId), "")
            ?.split('\u0001')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun save(prefs: SharedPreferences, sourceId: String, keywords: List<String>) {
        prefs.edit().putString(key(sourceId), keywords.joinToString("\u0001")).apply()
    }

    /** 标题命中任一关键词（忽略大小写）即命中屏蔽。 */
    fun hit(title: String, keywords: List<String>): Boolean =
        keywords.any { title.contains(it, ignoreCase = true) }
}
