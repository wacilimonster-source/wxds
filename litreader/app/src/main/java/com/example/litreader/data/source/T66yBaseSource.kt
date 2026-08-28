package com.example.litreader.data.source

import com.example.litreader.data.model.Post
import com.example.litreader.data.model.ThreadItem
import com.example.litreader.data.remote.HttpClient
import com.example.litreader.util.AdFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream

/**
 * t66y 各版块（thread0806.php）的公共解析逻辑。
 * 实测 fid=20（文学）与 fid=16（达盖尔贴图）列表行结构一致：
 * #ajaxtable tr.tr3 + h3 a[id^=t]，五列为 贊/文章/作者/回復/最後發表。
 */
abstract class T66yBaseSource : BookSource {

    protected val base = "https://www.t66y.com"

    override suspend fun getList(page: Int, category: String): List<ThreadItem> =
        withContext(Dispatchers.IO) {
            val cat = if (category.isNotEmpty()) "&type=$category" else ""
            val url = "$base/thread0806.php?fid=$fid&page=$page$cat"
            val doc = Jsoup.parse(ByteArrayInputStream(HttpClient.fetch(url)), null, base)
            val rows = doc.select("#ajaxtable tr.tr3")
            val items = ArrayList<ThreadItem>()
            for (tr in rows) {
                parseRow(tr, category)?.let { items.add(it) }
            }
            items
        }

    protected open fun parseRow(tr: Element, category: String): ThreadItem? {
        val a = tr.selectFirst("h3 a[id^=t]") ?: return null
        val tid = a.attr("id").removePrefix("t")
        val titleCell = tr.selectFirst("td.tal")
        val tag = titleCell?.let { c ->
            val clone = c.clone()
            clone.select("h3, span.thread_page, span.mark_gen, div.f12, div.s5").remove()
            // 标题旁的 ↑N 推荐数不属于版块标签，剔除
            clone.ownText().replace(Regex("↑\\s*\\d+"), "").trim()
        } ?: ""
        val tds = tr.select("td")
        val likes = tr.selectFirst("td span.sred, td span.s3, td span.b")?.text()?.trim() ?: ""
        val replies = if (tds.size > 3) tds[3].text().trim() else ""
        val last = tr.selectFirst("a.f10")
        val author = last?.parent()?.ownText()?.trim() ?: ""
        val ts = last?.attr("data-timestamp")?.toLongOrNull() ?: 0L
        val dateText = last?.text()?.trim() ?: ""
        return ThreadItem(
            tid = tid,
            title = a.text().trim(),
            author = author,
            timestamp = ts,
            dateText = dateText,
            href = a.attr("href"),
            category = category,
            tag = tag,
            likes = likes,
            replies = replies
        )
    }

    /** 详情统一走 read.php（列表里的 htm_data 静态页路径不可推算，直接访问会 404）。 */
    override suspend fun getThread(tid: String, onlyOp: Boolean): List<Post> =
        withContext(Dispatchers.IO) {
            val toread = if (onlyOp) "&toread=2" else "&toread=1"
            val url = "$base/read.php?tid=$tid$toread"
            val doc = Jsoup.parse(ByteArrayInputStream(HttpClient.fetch(url)), null, base)
            doc.select(".tpc_content").mapIndexedNotNull { idx, el ->
                AdFilter.clean(el)
                fixLazyImages(el)
                val content = el.html()
                if (content.isBlank()) return@mapIndexedNotNull null
                val table = ancestorTable(el)
                val author = table?.selectFirst("th.r_two b")?.text()?.trim() ?: ""
                val dateEl = table?.selectFirst(".tipad span[data-timestamp]")
                val floorText = table?.selectFirst(".tipad span.s3")?.text()?.trim() ?: ""
                val floor = Regex("""#(\d+)""").find(floorText)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                Post(
                    author = author,
                    contentHtml = content,
                    floor = floor,
                    dateText = dateEl?.text()?.trim() ?: ""
                )
            }
        }

    /** 向上找到楼层所在的 table 容器。 */
    private fun ancestorTable(el: Element): Element? {
        var p: Element? = el.parent()
        while (p != null) {
            if (p.tagName() == "table") return p
            p = p.parent()
        }
        return null
    }

    /** 站点图片是 ess-data 懒加载、无 src；离线渲染前把真实地址补到 src。 */
    protected fun fixLazyImages(el: Element) {
        el.select("img").forEach { img ->
            val src = img.attr("src").trim()
            if (src.isEmpty() || src.startsWith("data:")) {
                val real = img.attr("ess-data").trim().ifEmpty { img.attr("iyl-data").trim() }
                if (real.isNotEmpty()) img.attr("src", real)
            }
            img.removeAttr("ess-data")
            img.removeAttr("iyl-data")
            img.removeAttr("data-link")
        }
    }
}
