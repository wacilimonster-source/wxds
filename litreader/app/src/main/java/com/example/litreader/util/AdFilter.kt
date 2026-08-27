package com.example.litreader.util

import org.jsoup.nodes.Element

object AdFilter {
    private val adSelectors = listOf(
        "script", "iframe", "style", "noscript",
        "div[style*=ad]", "a[href*=ads]", "font[color=#999999]", "font[color=#999]"
    )
    private val adKeywords = listOf("广告", "推广", "代发", "加微信", "加vx", "联系方式", "出售", "代写")

    fun clean(el: Element) {
        el.select(adSelectors.joinToString(",")).remove()
        el.select("a").forEach { a ->
            if (adKeywords.any { a.text().contains(it) }) a.remove()
        }
    }
}
