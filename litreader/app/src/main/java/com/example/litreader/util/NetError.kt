package com.example.litreader.util

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 把底层网络异常翻译成用户能看懂的一句话，避免界面直接暴露英文堆栈信息。 */
object NetError {
    fun friendly(t: Throwable): String = when (t) {
        is UnknownHostException -> "网络不可用"
        is SocketTimeoutException -> "网络连接超时"
        is ConnectException -> "连接服务器失败"
        // 证书/主机名校验失败：多为代理或运营商劫持，重试或换网络即可
        is SSLException -> "网络被拦截（证书校验失败），请重试或更换网络"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "网络错误"
    }
}
