package com.example.litreader.ui.reader

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import com.example.litreader.data.model.Post
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** 图片占位的可点击 span（点击进画廊）。 */
class ImageLinkSpan(val url: String) : ClickableSpan() {
    override fun onClick(widget: View) {}
    override fun updateDrawState(ds: android.text.TextPaint) {
        ds.isUnderlineText = false
    }
}

/**
 * 论坛正文 HTML → 原生 Spannable（阅读器排版）。
 * 支持段落/换行、粗斜下划线、font 颜色、引用块（缩进+浅色）、图片（占位 chip）；
 * 楼层小节标记「第 N 樓」按开关插入，并记录楼层→字符偏移供跳转。
 */
class HtmlToSpans(
    private val subtleColor: Int,
    private val showFloorMarks: Boolean,
    private val indentPx: Int
) {
    val imageUrls = LinkedHashSet<String>()
    val floorOffsets = LinkedHashMap<Int, Int>()

    fun build(posts: List<Post>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (p in posts) {
            if (showFloorMarks) {
                val ms = sb.length
                sb.append("第 ${p.floor} 樓")
                sb.setSpan(RelativeSizeSpan(0.72f), ms, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(subtleColor), ms, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                floorOffsets[p.floor] = ms
            }
            appendNode(sb, Jsoup.parseBodyFragment(p.contentHtml).body())
            while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
            sb.append("\n\n")
        }
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun appendNode(sb: SpannableStringBuilder, node: Node) {
        when (node) {
            is TextNode -> sb.append(node.text().replace("\n", ""))
            is Element -> {
                when (node.tagName().lowercase()) {
                    "br" -> sb.append('\n')
                    "img" -> {
                        val src = node.attr("src").trim()
                        if (src.startsWith("http")) {
                            imageUrls.add(src)
                            val ms = sb.length
                            sb.append("［图］")
                            sb.setSpan(ImageLinkSpan(src), ms, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(BackgroundColorSpan(0x2E8A7F70), ms, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    "script", "style", "h5" -> return
                    "blockquote" -> {
                        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
                        val ms = sb.length
                        node.childNodes().forEach { appendNode(sb, it) }
                        if (sb.length > ms) {
                            sb.setSpan(
                                LeadingMarginSpan.Standard(indentPx, indentPx),
                                ms, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            sb.setSpan(ForegroundColorSpan(subtleColor), ms, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
                    }
                    "b", "strong" -> wrap(sb, node, StyleSpan(Typeface.BOLD))
                    "i", "em" -> wrap(sb, node, StyleSpan(Typeface.ITALIC))
                    "u" -> wrap(sb, node, UnderlineSpan())
                    "font" -> {
                        val ms = sb.length
                        node.childNodes().forEach { appendNode(sb, it) }
                        parseColor(node.attr("color"))?.let {
                            sb.setSpan(ForegroundColorSpan(it), ms, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    "p", "div" -> {
                        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
                        node.childNodes().forEach { appendNode(sb, it) }
                        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
                    }
                    else -> node.childNodes().forEach { appendNode(sb, it) }
                }
            }
        }
    }

    private fun wrap(sb: SpannableStringBuilder, node: Element, span: Any) {
        val ms = sb.length
        node.childNodes().forEach { appendNode(sb, it) }
        if (sb.length > ms) sb.setSpan(span, ms, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun parseColor(raw: String): Int? = try {
        when {
            raw.isBlank() -> null
            raw.startsWith("#") -> Color.parseColor(raw)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
