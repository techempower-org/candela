package `in`.jphe.storyvox.data.source.companion

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.SearchQuery
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1208 — the audio↔text counterpart for a fiction the user is
 * viewing: a LibriVox audiobook's Project Gutenberg text, or a Gutenberg
 * text's LibriVox narration. Null [fictionId] never happens — a match
 * always carries the companion's real `FictionSource` id so the UI can
 * deep-link to its detail.
 */
data class CompanionMatch(
    /** The companion's source id (`gutenberg` or `librivox`). */
    val sourceId: String,
    /** The companion's `FictionSource` fiction id, for navigation. */
    val fictionId: String,
    /** Display title (the companion's, or the originating work's when known). */
    val title: String,
    /** Whether the companion is the AUDIO or the TEXT side. */
    val kind: Kind,
) {
    enum class Kind { AUDIO, TEXT }
}

/**
 * Issue #1208 — pairs open audiobooks with their open text counterparts
 * (LibriVox ↔ Gutenberg) so the UI can offer a "read along / listen along"
 * companion.
 *
 * Lives in `:core-data` and works purely over the **public** source models
 * + the injected `FictionSource` map (the same multibinding `FictionRepository`
 * uses), so it never reaches into a leaf source's internals — honouring the
 * leaf-source isolation rule (sources depend on core-data, not each other).
 *
 * The join key is [FictionSummary.companionSourceUrl]: LibriVox publishes its
 * recording's `url_text_source` (the Gutenberg page it was read from). That
 * makes the forward direction a pure parse, and the reverse direction a
 * title search **verified** by an exact ebook-id back-link — no fuzzy
 * false positives, and no LibriVox↔Gutenberg id table to maintain.
 */
@Singleton
class CompanionResolver @Inject constructor(
    private val sources: Map<String, @JvmSuppressWildcards FictionSource>,
) {

    /**
     * The audio↔text companion for [fiction], or null when there's no
     * high-confidence match or the companion's source is disabled. Safe to
     * call for any fiction — non-LibriVox/Gutenberg sources return null.
     */
    suspend fun companionFor(fiction: FictionSummary): CompanionMatch? = when (fiction.sourceId) {
        SourceIds.LIBRIVOX -> textCompanionOf(fiction)
        GutenbergRef.SOURCE_ID -> audioCompanionOf(fiction)
        else -> null
    }

    /**
     * LibriVox audiobook → its Project Gutenberg text. The recording's
     * published [FictionSummary.companionSourceUrl] back-link parses straight
     * to the ebook id; we only surface it when the Gutenberg source is
     * actually enabled (otherwise the deep-link would dead-end).
     */
    private fun textCompanionOf(audio: FictionSummary): CompanionMatch? {
        val ebookId = GutenbergRef.parseEbookIdFromUrl(audio.companionSourceUrl) ?: return null
        if (sources[GutenbergRef.SOURCE_ID] == null) return null
        return CompanionMatch(
            sourceId = GutenbergRef.SOURCE_ID,
            fictionId = GutenbergRef.fictionIdFor(ebookId),
            title = audio.title,
            kind = CompanionMatch.Kind.TEXT,
        )
    }

    /**
     * Project Gutenberg text → its LibriVox narration. LibriVox exposes no
     * by-ebook-id lookup, so we search by title and then **verify** each
     * candidate's published back-link resolves to the SAME ebook id — turning
     * a fuzzy substring search into an exact match. A book with no LibriVox
     * recording (or whose recording back-links elsewhere) yields null rather
     * than a wrong guess.
     */
    private suspend fun audioCompanionOf(text: FictionSummary): CompanionMatch? {
        val targetEbookId = GutenbergRef.ebookIdFromFictionId(text.id) ?: return null
        val librivox = sources[SourceIds.LIBRIVOX] ?: return null
        if (text.title.isBlank()) return null

        val candidates = when (val result = librivox.search(SearchQuery(term = text.title))) {
            is FictionResult.Success -> result.value.items
            else -> return null
        }
        val match = candidates.firstOrNull {
            GutenbergRef.parseEbookIdFromUrl(it.companionSourceUrl) == targetEbookId
        } ?: return null

        return CompanionMatch(
            sourceId = SourceIds.LIBRIVOX,
            fictionId = match.id,
            title = match.title,
            kind = CompanionMatch.Kind.AUDIO,
        )
    }
}
