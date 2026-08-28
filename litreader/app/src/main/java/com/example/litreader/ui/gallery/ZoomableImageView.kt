package com.example.litreader.ui.gallery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * 画廊用可缩放图片：双指缩放（1x–5x）、双击放大、拖动平移、单击回调（用于隐藏工具栏）。
 * 缩放为 1x 时横向滑动让位给 ViewPager 翻页。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    fun interface TapListener { fun onSingleTap() }
    var tapListener: TapListener? = null

    private val matrix = Matrix()
    private var baseScale = 1f
    private var zoom = 1f
    private var offX = 0f
    private var offY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val oldS = totalScale()
                zoom = (zoom * d.scaleFactor).coerceIn(1f, MAX_SCALE)
                val newS = totalScale()
                if (newS != oldS) {
                    val ratio = newS / oldS
                    offX = d.focusX - (d.focusX - offX) * ratio
                    offY = d.focusY - (d.focusY - offY) * ratio
                    clampAndApply()
                }
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    private val gesture = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom > 1f) {
                    offX -= dx
                    offY -= dy
                    clampAndApply()
                }
                return zoom > 1f
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1f) {
                    reset()
                } else {
                    offX = e.x - (e.x - offX) * DOUBLE_TAP_SCALE
                    offY = e.y - (e.y - offY) * DOUBLE_TAP_SCALE
                    zoom = DOUBLE_TAP_SCALE
                    clampAndApply()
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                tapListener?.onSingleTap()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    private fun totalScale() = baseScale * zoom

    fun setBitmap(b: Bitmap) {
        setImageBitmap(b)
        reset()
    }

    fun reset() {
        val d = drawable ?: return
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (iw <= 0 || ih <= 0 || width == 0 || height == 0) return
        baseScale = min(width / iw, height / ih)
        zoom = 1f
        offX = (width - iw * baseScale) / 2f
        offY = (height - ih * baseScale) / 2f
        clampAndApply()
    }

    private fun clampAndApply() {
        val d = drawable ?: return
        val iw = d.intrinsicWidth * totalScale()
        val ih = d.intrinsicHeight * totalScale()
        offX = if (iw <= width) (width - iw) / 2f else offX.coerceIn(width - iw, 0f)
        offY = if (ih <= height) (height - ih) / 2f else offY.coerceIn(height - ih, 0f)
        val s = totalScale()
        matrix.reset()
        matrix.postScale(s, s)
        matrix.preTranslate(offX, offY)
        imageMatrix = matrix
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null) reset()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(zoom > 1f)
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        scaleDetector.onTouchEvent(event)
        gesture.onTouchEvent(event)
        return true
    }

    companion object {
        private const val MAX_SCALE = 5f
        private const val DOUBLE_TAP_SCALE = 2.5f
    }
}
