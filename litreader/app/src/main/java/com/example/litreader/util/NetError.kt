package com.example.litreader.util

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 把底层网络异常翻译成用户能看懂的一句话，避免界面直接暴露英文堆栈信息。 */
object NetError {
    fun friendly(t: Throwable): String = when (t) {
        is UnknownHostException -> "网络不可用"
        is SocketTimeoutException -> "网络连接超时"
        is ConnectException -> "连接服务器失败"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "网络错误"
    }
}
