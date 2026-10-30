package com.example.litreader.util

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import com.example.litreader.R

/** 列表首屏加载占位：卡片骨架 + 呼吸透明度动画。 */
object Skeleton {

    fun show(view: View) {
        val existing = view.getTag(R.id.skeletonAnim) as? Animator
        if (existing == null) {
            val anim = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.45f).apply {
                duration = 650
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
            }
            view.setTag(R.id.skeletonAnim, anim)
            view.visibility = View.VISIBLE
            anim.start()
        } else {
            view.visibility = View.VISIBLE
            if (!existing.isRunning) existing.start()
        }
    }

    fun hide(view: View) {
        (view.getTag(R.id.skeletonAnim) as? Animator)?.cancel()
        view.visibility = View.GONE
        view.alpha = 1f
    }
}
