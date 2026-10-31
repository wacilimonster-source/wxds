package com.example.litreader.util

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 触感反馈三档：轻震（chip/页签选中、翻页）、中震（收藏/保存成功）、
 * 成功双击（标记已读完）。系统关闭触感时 performHapticFeedback 自动静默。
 */
object Haptics {

    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun key(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** 两次轻震组成的「成功」节奏，间隔 80ms。 */
    fun confirm(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        Handler(Looper.getMainLooper()).postDelayed({
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }, 80)
    }
}
