package `in`.jphe.storyvox.source.bookshare.parse

/**
 * Issue #1002 — a parsed DAISY book, text only.
 *
 * A TTS app re-narrates with its own voices, so we keep only the text +
 * structure; DAISY's bundled audio and SMIL timing are dropped. See
 * [DaisyParser].
 */
data class DaisyBook(
    val title: String?,
    val author: String?,
    val chapters: List<DaisyChapter>,
)

/**
 * One DAISY top-level section, in the (title, htmlBody, plainBody) shape the
 * source layer maps onto
 * [`ChapterContent`][in.jphe.storyvox.data.source.model.ChapterContent].
 */
data class DaisyChapter(
    val id: String,
    val title: String,
    val htmlBody: String,
    val plainBody: String,
)

/** Thrown when a DAISY document can't be parsed. */
class DaisyParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
