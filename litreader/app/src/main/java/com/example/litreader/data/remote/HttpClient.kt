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
     * DoH 多供应商解析（视频站群专用）：部分网络对这些域名做 DNS 污染
     * （返回 Clash fake-ip 段 198.18/15、运营商劫持 IP 或 2001:2::/48 等假地址），
     * 连假机表现为「证书校验失败」或 403。解析顺序：
     * 1) DoH 供应商逐个尝试（阿里 223.5.5.5 / 223.6.6.6 / DNSPod 1.12.12.12，5s 超时，IP 字面量引导）
     * 2) 全挂 → 上次成功解析的落盘 IP（媒体域名 IP 相对稳定）
     * 3) 再不行 → 系统 DNS，结果同样过假 IP 过滤
     * 所有结果先过滤非公网地址再使用，杜绝连上劫持机后拿到 403/假页面。
     */
    /** 解析结果缓存项（匿名对象内不能声明类，放外层）。 */
    private class DohEntry(val addrs: List<InetAddress>, val expireAt: Long)

    /** DoH JSON API（国内公共 DNS，IP 直连引导；均实测可用）。 */
    private val dohProviders = listOf(
        "https://223.5.5.5/resolve?name=%s&type=1",
        "https://223.6.6.6/resolve?name=%s&type=1",
        "https://1.12.12.12/dns-query?name=%s&type=1"
    )

    /** 记忆可用 IP 的落盘（App 启动注入），进程内副本先行。 */
    @Volatile
    private var dnsStore: android.content.SharedPreferences? = null
    private val lastGoodInMem = ConcurrentHashMap<String, List<InetAddress>>()

    /** App.onCreate 注入：DoH 全挂时用「上次解析成功」的 IP 兜底。 */
    fun initDnsStore(prefs: android.content.SharedPreferences) {
        dnsStore = prefs
    }

    /** 非公网/劫持地址过滤：环回、内网、链路本地、组播、CGNAT、Clash fake-ip、基准测试段一律拒收。 */
    private fun trustworthy(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return false
        val h = addr.hostAddress ?: return false
        if (h.contains(":")) return !h.startsWith("2001:2:")  // 2001:2::/48 假地址（本机实测出现过）
        val o = h.split('.').map { it.toIntOrNull() ?: -1 }
        if (o.size != 4) return true
        val (a, b, c) = o
        return when {
            a == 0 || a == 10 || a == 127 -> false                    // 0/8、10/8、环回
            a == 100 && b in 64..127 -> false                         // CGNAT 100.64/10
            a == 192 && b == 0 && c == 0 -> false                     // 192.0.0.0/24
            a == 198 && (b == 18 || b == 19) -> false                 // 198.18/15 fake-ip
            else -> true
        }
    }

    private val dohDns = object : Dns {
        // DoH 引导客户端不设业务代理：国内公共 DNS 直连可达，且要避免代理侧污染干扰
        private val http = baseBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        private val cache = ConcurrentHashMap<String, DohEntry>()
        private val ttlMs = 5 * 60 * 1000L
        private val fallbackTtlMs = 60 * 1000L

        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            cache[hostname]?.takeIf { it.expireAt > now }?.let { return it.addrs }
            val viaDoh = runCatching { dohLookup(hostname) }.getOrNull().orEmpty()
            if (viaDoh.isNotEmpty()) {
                remember(hostname, viaDoh)
                cache[hostname] = DohEntry(viaDoh, now + ttlMs)
                return viaDoh
            }
            // DoH 全挂：优先上次成功 IP（真实机），拼接系统 DNS 的过滤结果兜底；
            // OkHttp 会按顺序尝试，前一个 IP 失效自动换下一个
            val resolved = (recall(hostname) + systemLookup(hostname)).distinct()
            if (resolved.isNotEmpty()) {
                cache[hostname] = DohEntry(resolved, now + fallbackTtlMs)
                return resolved
            }
            // 全部不可信：抛 UnknownHost（界面译为「网络不可用」），绝不连劫持机拿 403
            throw java.net.UnknownHostException(hostname)
        }

        private fun systemLookup(hostname: String): List<InetAddress> =
            runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
                .filter(::trustworthy)

        /** 供应商逐个尝试，返回第一个给出可信公网 IP 的结果。 */
        private fun dohLookup(hostname: String): List<InetAddress> {
            for (tpl in dohProviders) {
                val ips = runCatching {
                    val req = Request.Builder()
                        .url(tpl.format(hostname))
                        .header("Accept", "application/dns-json")
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching emptyList<InetAddress>()
                        val ans = JSONObject(resp.body?.string() ?: return@runCatching emptyList<InetAddress>())
                            .optJSONArray("Answer") ?: return@runCatching emptyList<InetAddress>()
                        val ips = ArrayList<InetAddress>(ans.length())
                        for (i in 0 until ans.length()) {
                            val o = ans.optJSONObject(i) ?: continue
                            if (o.optInt("type") != 1) continue
                            // IP 字面量在本地解析，不会再触发 DNS
                            runCatching { InetAddress.getByName(o.optString("data")) }
                                .getOrNull()?.takeIf { trustworthy(it) }?.let { ips.add(it) }
                        }
                        ips
                    }
                }.getOrNull().orEmpty()
                if (ips.isNotEmpty()) return ips
            }
            return emptyList()
        }
    }

    private fun remember(host: String, ips: List<InetAddress>) {
        lastGoodInMem[host] = ips
        dnsStore?.edit()
            ?.putString("ips_$host", ips.joinToString(",") { it.hostAddress.orEmpty() })
            ?.apply()
    }

    private fun recall(host: String): List<InetAddress> {
        lastGoodInMem[host]?.let { return it }
        val stored = dnsStore?.getString("ips_$host", null)?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return stored.split(',').mapNotNull {
            runCatching { InetAddress.getByName(it.trim()) }.getOrNull()
        }.filter(::trustworthy).also { if (it.isNotEmpty()) lastGoodInMem[host] = it }
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
