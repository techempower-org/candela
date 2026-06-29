package `in`.jphe.storyvox.data.source.companion

import `in`.jphe.storyvox.data.source.SourceIds

/**
 * Issue #1208 — shared Project Gutenberg id helpers for the cross-source
 * audio↔text companion matcher.
 *
 * The matcher ([CompanionResolver]) runs in `:core-data` and may only touch
 * the *public* source models — it can't reach the `internal`
 * `GutenbergTextApi.parseGutenbergId` inside `:source-librivox` (leaf-source
 * isolation: sources depend on core-data, not the reverse). So the
 * URL→ebook-id parse lives here, where both the matcher and (eventually, via
 * a follow-up dedupe) the LibriVox source can share it.
 *
 * The numeric "ebook id" (e.g. `1342`) is Gutenberg's catalog id; the
 * `FictionSource` id for the same work is `gutenberg:1342`
 * ([fictionIdFor] / [ebookIdFromFictionId]).
 */
object GutenbergRef {

    /** The Gutenberg [FictionSource][in.jphe.storyvox.data.source.FictionSource] id. */
    const val SOURCE_ID: String = SourceIds.GUTENBERG

    /**
     * Pull the numeric ebook id out of a gutenberg.org URL (LibriVox's
     * `url_text_source` back-link), or null when the URL isn't a Gutenberg
     * book. Mirrors the URL shapes the LibriVox `GutenbergTextApi` handles:
     * `/ebooks/<id>`, `/etext/<id>`, `/files/<id>/…`, `/cache/epub/<id>/…`.
     */
    fun parseEbookIdFromUrl(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return EBOOK_URL_PATTERN.find(trimmed)?.groupValues?.getOrNull(1)
    }

    /**
     * The numeric ebook id encoded in a `gutenberg:<id>` FictionSource id,
     * or null if [fictionId] isn't a Gutenberg id. Mirrors GutenbergSource's
     * own `substringAfter("gutenberg:")` convention.
     */
    fun ebookIdFromFictionId(fictionId: String): String? =
        fictionId.substringAfter("gutenberg:", missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() }

    /** The `gutenberg:<id>` FictionSource id for a numeric [ebookId]. */
    fun fictionIdFor(ebookId: String): String = "gutenberg:$ebookId"

    private val EBOOK_URL_PATTERN: Regex = Regex(
        """^https?://(?:www\.)?gutenberg\.org/""" +
            """(?:ebooks|etext|files|cache/epub)/(\d+)(?:[/?#.].*)?$""",
        RegexOption.IGNORE_CASE,
    )
}
