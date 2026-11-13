package com.example.litreader.util

import android.view.View
import com.example.litreader.R
import com.google.android.material.snackbar.Snackbar

/**
 * 统一轻提示：深墨底圆角 snackbar（可带撤销/查看动作），
 * 全站替代系统 Toast。anchorView 用于把提示抬到阅读器菜单/底栏之上。
 */
object UiTips {

    fun snack(
        view: View,
        text: CharSequence,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        anchor: View? = null,
        long: Boolean = false
    ) {
        val bar = Snackbar.make(
            view, text,
            if (long || onAction != null) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        )
        // Snackbar 默认贴在窗口底部，会压住 MainActivity 的底部导航；
        // 未显式指定 anchor 时，自动锚到底部导航（提示落在导航上沿）。
        val finalAnchor = anchor ?: view.rootView.findViewById<View>(R.id.bottomNav)
        finalAnchor?.let { bar.anchorView = it }
        bar.view.background = androidx.core.content.ContextCompat.getDrawable(
            view.context, R.drawable.bg_snack
        )
        bar.view.elevation = view.context.resources.displayMetrics.density * 6
        val pad = (16 * view.resources.displayMetrics.density).toInt()
        bar.view.setPadding(pad, (10 * view.resources.displayMetrics.density).toInt(), pad, (10 * view.resources.displayMetrics.density).toInt())
        bar.setTextMaxLines(2)
        bar.setTextColor(androidx.core.content.ContextCompat.getColor(view.context, R.color.snackFg))
        if (actionLabel != null && onAction != null) {
            bar.setAction(actionLabel) { onAction() }
            bar.setActionTextColor(androidx.core.content.ContextCompat.getColor(view.context, R.color.snackAction))
        }
        bar.show()
    }
}
