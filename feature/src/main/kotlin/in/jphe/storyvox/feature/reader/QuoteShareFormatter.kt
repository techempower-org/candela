package `in`.jphe.storyvox.feature.reader

/**
 * Issue #1234 — pure formatter that renders a single highlighted quote with
 * book attribution for the share-sheet / clipboard. Compose- and Android-free
 * by design, mirroring [ReaderHighlightSelection]'s pure offset helpers and
 * core-data's `AnnotationExportFormatter`: keeping the formatting a pure
 * function makes the exact shared text unit-testable without standing up a
 * Compose runtime, a Context, or the share Intent.
 *
 * The dispatch half (Intent.ACTION_SEND + ClipboardManager + Toast) lives in
 * [QuoteShareActions], which is Android-coupled and not unit-tested; this file
 * owns the part worth pinning — the exact string a reader copies or posts.
 *
 * ## Output shape
 * ```
 * “Selected text here.”
 *
 * — Author Name, Book Title, Chapter Title
 * via Candela
 * ```
 * Curly quotes “ ” (U+201C/U+201D) match the app's existing plain-text quote
 * convention (`AnnotationExportFormatter.plain`) and read better in a social
 * post than straight ASCII quotes. The attribution line is an em-dash (—)
 * followed by the non-blank metadata joined with commas; the brand signature
 * line is always last.
 */
object QuoteShareFormatter {

    /** Brand signature appended as the final line. Not a string resource:
     *  it's brand chrome (the product name never translates), and keeping it
     *  here leaves the formatter self-contained + pure-testable. */
    const val ATTRIBUTION = "via Candela"

    /**
     * Format [quote] with whatever attribution metadata is available.
     *
     * Any of [author] / [bookTitle] / [chapterTitle] may be blank (e.g. a
     * source with no author, or a chapter loaded before its title resolves);
     * blank parts are dropped from the attribution line and the surviving
     * parts are comma-joined, so the dash never dangles a stray comma. When
     * every metadata field is blank the attribution line is omitted entirely
     * and only the quote + brand signature remain.
     *
     * The quote's internal whitespace — newlines from a multi-line selection,
     * runs of spaces — is collapsed to single spaces and trimmed, so a
     * paragraph-spanning highlight shares as one clean line rather than
     * leaking the reader's line wrapping into the post.
     */
    fun format(
        quote: String,
        author: String,
        bookTitle: String,
        chapterTitle: String,
    ): String {
        val cleanQuote = WHITESPACE_RUN.replace(quote, " ").trim()
        val attribution = listOf(author, bookTitle, chapterTitle)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

        return buildString {
            append('“').append(cleanQuote).append('”')
            if (attribution.isNotEmpty()) {
                append("\n\n— ").append(attribution)
            }
            append('\n').append(ATTRIBUTION)
        }
    }

    /** Any run of whitespace (newlines, tabs, repeated spaces) → one space. */
    private val WHITESPACE_RUN = Regex("\\s+")
}
