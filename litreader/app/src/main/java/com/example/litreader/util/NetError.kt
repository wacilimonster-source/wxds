package com.example.litreader.util

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 把底层网络异常翻译成用户能看懂的一句话，避免界面直接暴露英文堆栈信息。 */
object NetError {
    fun friendly(t: Throwable): String {
        // Media3/OkHttp 会把底层异常层层包裹（PlaybackException → … → SSLException），
        // 沿 cause 链逐层找第一个能识别的异常来翻译。
        var cur: Throwable? = t
        repeat(5) {
            when (val e = cur) {
                is UnknownHostException -> return "网络不可用"
                is SocketTimeoutException -> return "网络连接超时"
                is ConnectException -> return "连接服务器失败"
                // 证书/主机名校验失败：多为代理或运营商劫持，重试或换网络即可
                is SSLException -> return "网络被拦截（证书校验失败），请重试或更换网络"
                is HttpStatusException -> return when (e.code) {
                    401, 403 -> "视频服务器拒绝访问（HTTP ${e.code}），请重试或更换网络"
                    404, 410 -> "视频不存在或已下架（HTTP ${e.code}）"
                    in 500..599 -> "视频服务器暂时不可用（HTTP ${e.code}），请稍后重试"
                    else -> "服务器返回异常（HTTP ${e.code}），请重试"
                }
            }
            cur = cur?.cause
        }
        return t.message?.takeIf { it.isNotBlank() } ?: "网络错误"
    }
}
