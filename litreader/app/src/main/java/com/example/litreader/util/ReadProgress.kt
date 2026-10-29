package com.example.litreader.util

import android.content.Context

/** reader_pos prefs 里的每贴阅读进度（tid → 0<ratio≤1），列表页/书架卡片共用。 */
object ReadProgress {
    fun map(ctx: Context): Map<String, Float> {
        val map = HashMap<String, Float>()
        for ((k, v) in ctx.getSharedPreferences("reader_pos", Context.MODE_PRIVATE).all) {
            if (!k.startsWith("pos_")) continue
            val ratio = (v as? String)?.split(":")?.getOrNull(1)?.toFloatOrNull() ?: continue
            if (ratio > 0.005f) map[k.removePrefix("pos_")] = ratio
        }
        return map
    }
}
