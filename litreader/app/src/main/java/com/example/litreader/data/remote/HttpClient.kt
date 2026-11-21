package com.example.litreader.data.remote

import com.example.litreader.util.HttpStatusException
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object HttpClient {
    // 本地调试用：宿主机代理（模拟器经 10.0.2.2 访问宿主 loopback）。发布前必须为 false。
    // 只作用于论坛通道：视频站群域名常被代理侧 DNS 污染/改道，媒体链路必须直连才有意义。
    private const val USE_PROXY = false
    private const val PROXY_HOST = "10.0.2.2"
    private const val PROXY_PORT = 7897

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)

    private fun forumBuilder(): OkHttpClient.Builder = baseBuilder().apply {
        if (USE_PROXY) proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(PROXY_HOST, PROXY_PORT)))
    }

    /** 常规抓取（论坛/列表/详情）：严格证书校验 + 系统 DNS。论坛链路现状可达，不动。 */
    private val client by lazy { forumBuilder().build() }

    /**
     * DoH 优先解析：部分网络对视频站群 CDN 域名做 DNS 污染（返回随机运营商 IP），
     * App 连到假机后表现为「证书校验失败」或接口 403。阿里 DoH 实测可拿到真实 A 记录；
     * DoH 失败（被墙/超时）时回退系统解析，退化为原有行为。结果缓存 5 分钟减少往返。
     */
    /** DoH 缓存项（匿名对象内不能声明类，放外层）。 */
    private class DohEntry(val addrs: List<InetAddress>, val expireAt: Long)

    private val dohDns = object : Dns {
        // DoH 引导客户端不设业务代理：223.5.5.5 国内直连可达，且要避免代理侧污染干扰
        private val http = baseBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        private val cache = ConcurrentHashMap<String, DohEntry>()
        private val ttlMs = 5 * 60 * 1000L

        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            cache[hostname]?.takeIf { it.expireAt > now }?.let { return it.addrs }
            val viaDoh = runCatching { dohLookup(hostname) }.getOrNull().orEmpty()
            if (viaDoh.isNotEmpty()) {
                cache[hostname] = DohEntry(viaDoh, now + ttlMs)
                return viaDoh
            }
            return Dns.SYSTEM.lookup(hostname)
        }

        private fun dohLookup(hostname: String): List<InetAddress> {
            val req = Request.Builder()
                .url("https://223.5.5.5/resolve?name=$hostname&type=1")
                .header("Accept", "application/dns-json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val ans = JSONObject(resp.body?.string() ?: return emptyList())
                    .optJSONArray("Answer") ?: return emptyList()
                val ips = ArrayList<InetAddress>(ans.length())
                for (i in 0 until ans.length()) {
                    val o = ans.optJSONObject(i) ?: continue
                    if (o.optInt("type") != 1) continue
                    // IP 字面量在本地解析，不会再触发 DNS
                    runCatching { InetAddress.getByName(o.optString("data")) }
                        .getOrNull()?.let { ips.add(it) }
                }
                return ips
            }
        }
    }

    /** 视频站群抓取（播放器页/playerurl）：直连 + DoH + 严格证书校验，正常与被污染网络均可达真机。 */
    private val videoClient by lazy { baseBuilder().dns(dohDns).build() }

    /**
     * 媒体播放兜底客户端：直连 + DoH + 忽略证书校验。用于 ExoPlayer 取流
     * （m3u8/mp4/分段/AES key）：第三方站群证书可能临时不匹配，放宽后仍可起播；
     * 只承载公开媒体分段，无账号凭据，被劫持的最坏结果是取到垃圾流并播放失败。
     */
    private val mediaClient by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(tm), SecureRandom())
        baseBuilder()
            .dns(dohDns)
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** 播放器数据源共用同一 OkHttp（UA/超时口径一致）。 */
    fun okHttp(): OkHttpClient = client

    /** 媒体播放专用：直连 + DoH + 忽略证书校验，见 mediaClient 注释。 */
    fun mediaOkHttp(): OkHttpClient = mediaClient

    fun userAgent(): String = UA

    /** 抓取通道选择：论坛走系统 DNS 严格校验；视频站群走 DoH，严格优先、放宽兜底。 */
    enum class Tls { FORUM, MEDIA_STRICT, MEDIA_RELAXED }

    fun fetch(url: String, referer: String? = null, tls: Tls = Tls.FORUM): ByteArray {
        val cl = when (tls) {
            Tls.FORUM -> client
            Tls.MEDIA_STRICT -> videoClient
            Tls.MEDIA_RELAXED -> mediaClient
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply { referer?.let { header("Referer", it) } }
            .build()
        cl.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, url)
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /** 文本抓取（视频站 API/播放器页需要 Referer 伪装成嵌入页来源）。 */
    fun fetchText(url: String, referer: String? = null, tls: Tls = Tls.FORUM): String =
        String(fetch(url, referer, tls), Charsets.UTF_8)
}
