package com.example.litreader.util

import android.content.Context

/** reader_pos prefs 里的每贴阅读进度（tid → 0<ratio≤1），列表页/书架卡片共用。 */
object ReadProgress {
    private const val PREFS = "reader_pos"
    private const val KEY_DIRTY_PREFIX = "dirty_"

    fun map(ctx: Context): Map<String, Float> {
        val map = HashMap<String, Float>()
        for ((k, v) in prefs(ctx).all) {
            if (!k.startsWith("pos_")) continue
            val ratio = (v as? String)?.split(":")?.getOrNull(1)?.toFloatOrNull() ?: continue
            if (ratio > 0.005f) map[k.removePrefix("pos_")] = ratio
        }
        return map
    }

    /**
     * 阅读器/论坛详情退出时登记「这一条变过」。
     * 列表恢复时只回读这一条，不再整表重查已加载的所有页。
     */
    fun markDirty(ctx: Context, sourceId: String, tid: String) {
        if (sourceId.isEmpty() || tid.isEmpty()) return
        prefs(ctx).edit().putString(KEY_DIRTY_PREFIX + sourceId, tid).apply()
    }

    /** 取出并清空某分区的待刷新标记；没有则返回 null。 */
    fun consumeDirty(ctx: Context, sourceId: String): String? {
        val p = prefs(ctx)
        val key = KEY_DIRTY_PREFIX + sourceId
        val tid = p.getString(key, null) ?: return null
        p.edit().remove(key).apply()
        return tid
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
