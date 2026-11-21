package com.example.litreader.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object HttpClient {
    // 本地调试用：宿主机代理（模拟器经 10.0.2.2 访问宿主 loopback）。发布前必须为 false。
    private const val USE_PROXY = false
    private const val PROXY_HOST = "10.0.2.2"
    private const val PROXY_PORT = 7897

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (USE_PROXY) proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(PROXY_HOST, PROXY_PORT)))
        }
        .build()

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** 播放器数据源共用同一 OkHttp（UA/代理/超时口径一致）。 */
    fun okHttp(): OkHttpClient = client
    fun userAgent(): String = UA

    fun fetch(url: String, referer: String? = null): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply { referer?.let { header("Referer", it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /** 文本抓取（视频站 API/播放器页需要 Referer 伪装成嵌入页来源）。 */
    fun fetchText(url: String, referer: String? = null): String =
        String(fetch(url, referer), Charsets.UTF_8)
}
