package `in`.jphe.storyvox.source.audiobook.writer

/**
 * Inputs the M4B encoder consumes to write one chaptered audiobook. Pure
 * data; no Room / Android dependency. Built by `ExportFictionToAudiobookUseCase`
 * (in :core-playback) from DB rows, mirroring `EpubBook` from
 * :source-epub-writer.
 *
 * Issue #1003 — "Make your own audiobook." A finished export carries title +
 * author + optional cover and a chapter list; the encoder synthesizes each
 * chapter's PCM, encodes to AAC, muxes into a single `.m4b`, and writes a
 * QuickTime chapter-text track + iTunes metadata so audiobook players (Smart
 * AudioBook Player, BookPlayer, Apple Books, etc.) show the chapter list.
 */
data class AudiobookBook(
    val title: String,
    val author: String,
    /** Cover image bytes (JPEG/PNG), or null. Embedded as the `covr` atom in
     *  the iTunes metadata so players show artwork. */
    val cover: ByteArray? = null,
    val chapters: List<AudiobookChapter>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudiobookBook) return false
        return title == other.title &&
            author == other.author &&
            cover.contentEqualsNullable(other.cover) &&
            chapters == other.chapters
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + (cover?.contentHashCode() ?: 0)
        result = 31 * result + chapters.hashCode()
        return result
    }
}

/**
 * One chapter to narrate. [text] is the plain text fed to TTS; [title] labels
 * the chapter marker in the output file.
 */
data class AudiobookChapter(
    val title: String,
    val text: String,
)

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null) other == null else other != null && this.contentEquals(other)
