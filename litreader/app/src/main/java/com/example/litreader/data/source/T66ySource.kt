package com.example.litreader.data.source

import com.example.litreader.data.model.Post
import com.example.litreader.data.model.ThreadItem
import com.example.litreader.data.remote.HttpClient
import com.example.litreader.util.AdFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

class T66ySource : BookSource {
    override val id = "t66y"
    override val name = "草榴文学区"

    private val base = "https://www.t66y.com"
    private val fid = "20"

    override suspend fun getList(page: Int, category: String): List<ThreadItem> =
        withContext(Dispatchers.IO) {
            val cat = if (category.isNotEmpty()) "&type=$category" else ""
            val url = "$base/thread0806.php?fid=$fid&page=$page$cat"
            val doc = Jsoup.parse(ByteArrayInputStream(HttpClient.fetch(url)), null, base)
            val titles = doc.select("h3 > a[id^=t]")
            val metas = doc.select("a.f10")
            titles.mapIndexed { i, a ->
                val tid = a.attr("id").removePrefix("t")
                val author = metas.getOrNull(i)?.parent()?.ownText()?.trim() ?: ""
                val ts = metas.getOrNull(i)?.attr("data-timestamp")?.toLongOrNull() ?: 0L
                val dateText = metas.getOrNull(i)?.text()?.trim() ?: ""
                ThreadItem(tid, a.text().trim(), author, ts, dateText, a.attr("href"), category)
            }
        }

    override suspend fun getThread(tid: String, onlyOp: Boolean): List<Post> =
        withContext(Dispatchers.IO) {
            val toread = if (onlyOp) "&toread=2" else ""
            val url = "$base/read.php?tid=$tid$toread"
            val doc = Jsoup.parse(ByteArrayInputStream(HttpClient.fetch(url)), null, base)
            val blocks = doc.select(".tpc_content")
            blocks.mapIndexed { idx, el ->
                AdFilter.clean(el)
                Post("", el.html(), idx + 1)
            }
        }
}
