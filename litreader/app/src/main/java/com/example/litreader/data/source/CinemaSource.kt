package com.example.litreader.data.source

import com.example.litreader.data.remote.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

/**
 * 在綫成人影院（fid=22）：type=1..6 子分类（亞洲無碼/亞洲有碼/歐美無碼/動漫無碼/動漫有碼/其他電影）。
 * 视频帖的 OP 不直接带流地址：正文里是隐藏的第三方播放器页 iframe
 * （如 https://yuese99.com/video.html?id=..&time=..&sign=..，同套代码的 yuese99/sese2009/wuyebo 站群），
 * 播放器页脚本再用 id/time/sign 调 {cdn}/videos/playerurl 换取真实 m3u8/mp4。
 * 解析链路：read.php OP → iframe data-src → 播放器页取 CDN 基址 → playerurl API → 直链。
 */
class CinemaSource : T66yBaseSource(), VideoSource {
    override val id = "t66y_cine"
    override val name = "在綫成人影院"
    override val shortName = "影院"
    override val fid = "22"
    override val remotePageSize = 100
    override val style = SourceStyle.VIDEO

    override val categories = listOf(
        "" to "全部",
        "1" to "亞洲無碼",
        "2" to "亞洲有碼",
        "3" to "歐美無碼",
        "4" to "動漫無碼",
        "5" to "動漫有碼",
        "6" to "其他電影"
    )

    /** 播放器页脚本请求 CDN 用的兜底基址（站群当前共用；正常路径从页面脚本里现取）。 */
    private val FALLBACK_CDN = "https://cdn.js-static.cc"

    /** OP 里的全部视频：外链播放器页 iframe（含「點擊這里打開新視窗」锚点兜底）+ 裸流直链。 */
    override suspend fun getVideos(tid: String): List<VideoLink> = withContext(Dispatchers.IO) {
        val url = "$base/read.php?tid=$tid&toread=1&page=1"
        val body = HttpClient.fetchText(url)
        val doc = Jsoup.parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)), null, base)
        val op = doc.selectFirst(".tpc_content")
        if (op == null) {
            // 站点对部分请求返回门户/跳转页（无楼层正文）：给出可重试的明确错误
            android.util.Log.w("CinemaSource", "tid=$tid 无 OP（页面 ${body.length} 字节，${url}）")
            throw IllegalStateException("帖子正文未取到（站点返回 ${body.length} 字节）")
        }
        val links = ArrayList<VideoLink>()
        val seen = HashSet<String>()

        fun addPage(raw: String) {
            val pageUrl = raw.trim().replace("______", ".")
            if (!pageUrl.startsWith("http")) return
            if (!seen.add(pageUrl)) return
            val qs = pageUrl.substringAfter('?', "")
            val id = Regex("(^|&)id=(\\d+)").find(qs)?.groupValues?.get(2) ?: return
            val time = Regex("(^|&)time=(\\d+)").find(qs)?.groupValues?.get(2) ?: ""
            val sign = Regex("(^|&)sign=([0-9a-fA-F]+)").find(qs)?.groupValues?.get(2) ?: ""
            if (time.isEmpty() || sign.isEmpty()) return
            val host = runCatching { java.net.URI(pageUrl).host }.getOrNull() ?: return
            links += VideoLink(host, pageUrl, id, time, sign, "第 ${links.size + 1} 段")
        }

        // 隐藏 iframe：src 停在 about:blank，真实地址在 data-src；老帖直接写 src
        op.select("iframe").forEach { f ->
            val src = f.attr("data-src").trim().ifEmpty { f.attr("src").trim() }
            if (src.isNotEmpty() && !src.startsWith("about:")) addPage(src)
        }
        // 「點擊這里打開新視窗」外链（域名点被写成 ______ 防广告过滤）
        op.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.contains("video.html") || (href.contains("time=") && href.contains("sign="))) {
                addPage(href)
            }
        }
        // 裸流直链（部分老帖/特殊帖直接给 mp4/m3u8，含 <video src> / <source src>）
        val raw = op.html()
        Regex("""https?://[^\s"'<>\\]+\.(?:mp4|m3u8)[^\s"'<>\\]*""").findAll(raw).forEach { m ->
            val u = m.value.replace("______", ".")
            if (seen.add(u)) links += VideoLink("", u, "", "", "", "第 ${links.size + 1} 段")
        }
        if (links.isEmpty()) {
            android.util.Log.w(
                "CinemaSource",
                "tid=$tid 未提取到视频：iframe=${op.select("iframe").size} a=${op.select("a[href]").size} OP长度=${raw.length}"
            )
        }
        links
    }

    /** 播放器页 → CDN 基址 + 页参数 → playerurl API → 直链。任何一环失败都抛给上层提示重试。 */
    override suspend fun resolveStream(link: VideoLink): String = withContext(Dispatchers.IO) {
        if (link.pageUrl.contains(".mp4") || link.pageUrl.contains(".m3u8")) return@withContext link.pageUrl
        if (link.id.isEmpty() || link.time.isEmpty() || link.sign.isEmpty()) {
            throw IllegalStateException("视频页参数缺失")
        }

        // 1) 播放器页脚本里现取 CDN 基址与参数（基址可能调整，不能写死）。
        // 播放器页域名同样可能被污染/证书不匹配：严格(DoH)失败后降级忽略证书再试
        var cdn = FALLBACK_CDN
        var id = link.id
        var time = link.time
        var sign = link.sign
        runCatching {
            val page = runCatching {
                HttpClient.fetchText(link.pageUrl, referer = base, tls = HttpClient.Tls.MEDIA_STRICT)
            }.recoverCatching {
                HttpClient.fetchText(link.pageUrl, referer = base, tls = HttpClient.Tls.MEDIA_RELAXED)
            }.getOrThrow()
            Regex("""requestVideoUrl\(\s*"((?:[^"\\]|\\.)+)"""").find(page)?.let { m ->
                cdn = m.groupValues[1].replace("\\/", "/").replace("\\", "")
            }
            Regex("""videoParams\s*=\s*(\{[^}]*\})""").find(page)?.let { m ->
                val p = JSONObject(m.groupValues[1])
                p.optString("id").takeIf { it.isNotEmpty() }?.let { id = it }
                p.optString("time").takeIf { it.isNotEmpty() }?.let { time = it }
                p.optString("sign").takeIf { it.isNotEmpty() }?.let { sign = it }
            }
        } // 页面抓取失败不致命：参数从 iframe URL 兜底，基址用站群公共值

        // 2) playerurl API：JSON {error:0, content:{video_url}}
        // 该域名族在部分网络下被 DNS 污染（假机→证书不符/403）。MEDIA_STRICT 带 DoH 可直连
        // 真机（证书实测有效）；仍失败按 忽略证书 → 明文HTTP 逐级降级，JSON 结构强校验兜住假响应。
        val api = "$cdn/videos/playerurl?id=$id&t=$time&h=$sign"
        var body = ""
        var viaHttp = false
        var lastErr: Exception? = null
        for (mode in 0 until 4) {
            val http = mode == 3
            val url = if (http) api.replaceFirst("https://", "http://") else api
            val tls = when {
                mode <= 1 -> HttpClient.Tls.MEDIA_STRICT
                else -> HttpClient.Tls.MEDIA_RELAXED
            }
            try {
                body = HttpClient.fetchText(url, referer = "https://${link.host}/", tls = tls)
                viaHttp = http
                if (mode > 0) android.util.Log.w(
                    "CinemaSource", "playerurl 第 ${mode + 1} 次尝试成功（降级 http=$http）"
                )
                break
            } catch (e: Exception) {
                lastErr = e
                if (mode == 0) Thread.sleep(600) // 严格通道的第二次尝试前避开偶发抖动
            }
        }
        if (body.isEmpty()) throw lastErr ?: IllegalStateException("未取到视频地址")
        val json = JSONObject(body)
        if (json.optInt("error", -1) != 0) throw IllegalStateException(json.optString("message", "接口返回异常"))
        var videoUrl = json.optJSONObject("content")?.optString("video_url").orEmpty().trim()
        if (videoUrl.isEmpty()) throw IllegalStateException("未取到视频地址")
        // API 只能走明文 HTTP 时，说明该域名族的 TLS 在当前网络被阻断：直链一并转 http 提高起播率
        if (viaHttp && videoUrl.startsWith("https://")) {
            videoUrl = "http://" + videoUrl.removePrefix("https://")
        }
        videoUrl
    }
}
