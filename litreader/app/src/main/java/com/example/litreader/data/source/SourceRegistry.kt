package com.example.litreader.data.source

object SourceRegistry {
    val sources: List<BookSource> = listOf(T66ySource())
    fun get(id: String) = sources.firstOrNull { it.id == id }
}
