package com.example.litreader.ui.reader

import android.text.StaticLayout

/** 按行把 StaticLayout 切成页（Line-based pagination）。 */
object Paginator {

    data class Page(
        val startLine: Int,
        val endLine: Int,
        val top: Float,
        val bottom: Float,
        val startChar: Int
    )

    fun paginate(layout: StaticLayout, pageHeight: Float): List<Page> {
        val pages = ArrayList<Page>()
        if (layout.lineCount == 0) return pages
        var startLine = 0
        var line = 0
        while (line < layout.lineCount) {
            val relBottom = (layout.getLineBottom(line) - layout.getLineTop(startLine)).toFloat()
            if (relBottom > pageHeight && line > startLine) {
                pages.add(makePage(layout, startLine, line))
                startLine = line
            } else {
                line++
            }
        }
        pages.add(makePage(layout, startLine, layout.lineCount))
        return pages
    }

    private fun makePage(l: StaticLayout, start: Int, end: Int): Page = Page(
        startLine = start,
        endLine = end,
        top = l.getLineTop(start).toFloat(),
        bottom = l.getLineBottom(end - 1).toFloat(),
        startChar = l.getLineStart(start)
    )
}
