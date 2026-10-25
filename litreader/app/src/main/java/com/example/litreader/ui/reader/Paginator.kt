package com.example.litreader.ui.reader

import android.text.StaticLayout

/**
 * 按行把 StaticLayout 切成页（Line-based pagination）。
 * 行距倍数产生的额外空间会被 StaticLayout 全部堆进每行 box 底部的 descent 里
 * （getLineBottom(i) == getLineTop(i+1)），若按它分页，页尾会多挂一行距的空白，
 * 下边距视觉上比设置大一截。这里用 min(行底, 基线+字体正常下沉) 作为行的 ink 底。
 */
object Paginator {

    data class Page(
        val startLine: Int,
        val endLine: Int,
        val top: Float,
        val bottom: Float,
        val startChar: Int
    )

    /** inkDescentPx: TextPaint 未加行距的 fontMetrics.descent。 */
    fun paginate(layout: StaticLayout, pageHeight: Float, inkDescentPx: Float): List<Page> {
        val pages = ArrayList<Page>()
        if (layout.lineCount == 0) return pages
        var startLine = 0
        var line = 0
        while (line < layout.lineCount) {
            val relBottom = inkBottom(layout, line, inkDescentPx) - layout.getLineTop(startLine)
            if (relBottom > pageHeight && line > startLine) {
                pages.add(makePage(layout, startLine, line, inkDescentPx))
                startLine = line
            } else {
                line++
            }
        }
        pages.add(makePage(layout, startLine, layout.lineCount, inkDescentPx))
        return pages
    }

    private fun inkBottom(l: StaticLayout, line: Int, inkDescentPx: Float): Int =
        minOf(l.getLineBottom(line), l.getLineBaseline(line) + inkDescentPx.toInt())

    private fun makePage(l: StaticLayout, start: Int, end: Int, inkDescentPx: Float): Page = Page(
        startLine = start,
        endLine = end,
        top = l.getLineTop(start).toFloat(),
        bottom = inkBottom(l, end - 1, inkDescentPx).toFloat(),
        startChar = l.getLineStart(start)
    )
}
