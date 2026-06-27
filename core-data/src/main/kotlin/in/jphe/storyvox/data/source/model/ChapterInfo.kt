package `in`.jphe.storyvox.data.source.model

/**
 * Table-of-contents entry. Body bytes are NOT included — see [ChapterContent].
 */
data class ChapterInfo(
    val id: String,
    val sourceChapterId: String,
    val index: Int,
    val title: String,
    val publishedAt: Long? = null,
    val wordCount: Int? = null,
    /** Issue #1221 — audio-stream sources (radio) set this at TOC time so
     *  the chapter row carries the URL from creation, eliminating the race
     *  between download-worker [ChapterContent.audioUrl] and loadAndPlay. */
    val audioUrl: String? = null,
)
