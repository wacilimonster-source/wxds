package com.example.litreader.data.source

object SourceRegistry {
    val sources: List<BookSource> = listOf(LitSource(), GallerySource(), CinemaSource(), TechSource())
    fun get(id: String) = sources.firstOrNull { it.id == id }
    fun first() = sources.first()
}
