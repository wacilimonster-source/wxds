package com.example.litreader.util

/** HTTP 非 2xx 响应。只带状态码与 URL，界面侧由 NetError 翻译，不直接展示 URL。 */
class HttpStatusException(val code: Int, val url: String) :
    RuntimeException("HTTP $code for $url")
