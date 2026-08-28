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

class T66ySource : BookSource {
    override val id = "t66y"
    override val name = "草榴文学区"

    override val categories = listOf(
        "" to "全部",
        "1" to "現代奇幻",
        "2" to "古典武俠",
        "3" to "另類禁忌",
        "4" to "性愛技巧",
        "5" to "笑話連篇",
        "6" to "有声小说",
        "12" to "其他交流"
    )

    private val base = "https://www.t66y.com"
    private val fid = "20"

    override suspend fun getList(page: Int, category: String): List<ThreadItem> =
        withContext(Dispatchers.IO) {
            val cat = if (category.isNotEmpty()) "&type=$category" else ""
            val url = "$base/thread0806.php?fid=$fid&page=$page$cat"
            val doc = Jsoup.parse(ByteArrayInputStream(HttpClient.fetch(url)), null, base)
            val rows = doc.select("#ajaxtable tr.tr3")
            val items = ArrayList<ThreadItem>()
            for (tr in rows) {
                val item = parseRow(tr, category) ?: continue
                items.add(item)
            }
            items
        }

    private fun parseRow(tr: Element, category: String): ThreadItem? {
        val a = tr.selectFirst("h3 a[id^=t]") ?: return null
        val tid = a.attr("id").removePrefix("t")
        val titleCell = tr.selectFirst("td.tal")
        val tag = titleCell?.let { c ->
            val clone = c.clone()
            clone.select("h3, span.thread_page, span.mark_gen, div.f12, div.s5").remove()
            clone.ownText().trim()
        } ?: ""
        val likes = tr.selectFirst("td span.sred, td span.s3, td span.b")?.text()?.trim() ?: ""
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
            likes = likes
        )
    }

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
    private fun fixLazyImages(el: Element) {
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
