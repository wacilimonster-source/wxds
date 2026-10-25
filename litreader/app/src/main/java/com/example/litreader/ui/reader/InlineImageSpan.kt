package com.example.litreader.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan

/**
 * 内联图 span。
 * StaticLayout 会把行框按行距倍数整体放大（extra 堆在 descent），
 * 因此这里反推上报行框：frame = (图高 + 正常一行框) / mult，
 * 放大后行高恰为「图高 + 一行距」，图片在行框内垂直居中，前后留均匀呼吸空隙。
 */
class InlineImageSpan(
    private val bmp: Bitmap,
    private val w: Int,
    private val h: Int,
    private val spacingMult: Float,
    private val normalFrame: Int
) : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val f = paint.fontMetricsInt
            val frame = ((h + normalFrame) / spacingMult).toInt()
            val extra = (frame - (f.descent - f.ascent)) / 2
            fm.ascent = f.ascent - extra
            fm.descent = f.descent + extra
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
        return w
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, baseline: Int, bottom: Int, paint: Paint
    ) {
        val dy = (bottom - top - h) / 2f
        canvas.drawBitmap(bmp, null, RectF(x, top + dy, x + w, top + dy + h), paint)
    }
}
