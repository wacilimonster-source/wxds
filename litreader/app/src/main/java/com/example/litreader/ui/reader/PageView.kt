package com.example.litreader.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.text.Spanned
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * 阅读器单页：绘制共享 StaticLayout 的一页切片。
 * 点击左 1/3 上一页、右 1/3 下一页、中间呼出菜单；命中图片占位则进画廊。
 */
class PageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callbacks {
        fun onZoneTap(isNext: Boolean)
        fun onCenterTap()
        fun onImageTap(url: String)
    }

    var callbacks: Callbacks? = null
    private var layout: StaticLayout? = null
    private var page: Paginator.Page? = null

    private val gesture = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val l = layout ?: return true
                val p = page ?: return true
                val text = l.text as? Spanned
                if (text != null) {
                    val line = l.getLineForVertical((e.y - paddingTop + p.top).toInt())
                    val off = l.getOffsetForHorizontal(line, e.x - paddingLeft)
                    val hit = text.getSpans(off, off, ImageLinkSpan::class.java).firstOrNull()
                    if (hit != null) {
                        callbacks?.onImageTap(hit.url)
                        return true
                    }
                }
                val w = width.toFloat()
                when {
                    e.x < w / 3f -> callbacks?.onZoneTap(false)
                    e.x > w * 2f / 3f -> callbacks?.onZoneTap(true)
                    else -> callbacks?.onCenterTap()
                }
                return true
            }
        }
    )

    fun bind(l: StaticLayout, p: Paginator.Page) {
        layout = l
        page = p
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        val p = page ?: return
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(paddingLeft.toFloat(), paddingTop - p.top)
        l.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gesture.onTouchEvent(event)
        return true
    }
}
