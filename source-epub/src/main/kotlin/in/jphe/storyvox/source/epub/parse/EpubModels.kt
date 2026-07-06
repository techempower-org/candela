package `in`.jphe.storyvox.source.epub.parse

/**
 * Issue #235 — minimal EPUB model. Captures only what storyvox's
 * reader/audiobook needs: title, author, cover (optional), and an
 * ordered list of chapters with their per-chapter HTML bodies.
 *
 * Anything more (TOC depth, metadata Dublin Core entries beyond
 * title/creator, semantic markup) is out of scope — storyvox renders
 * the spine in order, applies its existing HTML→plaintext stripper,
 * and feeds the engine. The pipeline downstream doesn't care about
 * EPUB-specific richness.
 */
data class EpubBook(
    /** `<dc:title>` from the OPF metadata block. */
    val title: String,
    /** `<dc:creator>` if present, otherwise empty string (preserves the
     *  FictionSummary contract of non-null author). */
    val author: String,
    /** Spine items in reading order. Each carries the raw HTML body
     *  read out of the zip. */
    val chapters: List<EpubChapter>,
    /** Optional cover image href resolved relative to the OPF. The
     *  reader doesn't currently render it but FictionSummary carries
     *  a cover field, so we expose it for future parity. */
    val coverHref: String? = null,
)

data class EpubChapter(
    /** Spine idref / manifest id — stable across re-opens of the same
     *  EPUB. Used as the chapter id storyvox persists. */
    val id: String,
    /** Display title. EPUB doesn't always provide one per chapter
     *  (the spine is just an ordered list of HTML hrefs); when missing
     *  we fall back to "Chapter N" per the index. */
    val title: String,
    /** 0-based position in the spine. */
    val index: Int,
    /** Sanitized HTML body of the chapter file. */
    val htmlBody: String,
    /**
     * Issue #1619 — optional pre-computed plaintext body. When non-null,
     * [`in`.jphe.storyvox.source.epub.EpubSource.chapter] uses it verbatim
     * as [`in`.jphe.storyvox.data.source.model.ChapterContent.plainBody]
     * instead of running [htmlBody] through the tag-stripper.
     *
     * The reader, the TTS `SentenceChunker`, and paragraph-level navigation
     * (`paragraphHeadIndices`) all consume `plainBody`, and paragraph nav
     * infers paragraph breaks from blank lines (`\n\n`) in that string. The
     * plaintext-import path (#1000) sets this straight from the raw UTF-8
     * file so paragraph and hard line breaks — the whole point of scripts,
     * verse, and teleprompter text — survive. Routing plaintext through the
     * whitespace-collapsing stripper flattened every newline to a space
     * (#1619) and leaked literal `&`-entities from the `<pre>` escaping.
     *
     * Real EPUB chapters leave this null and keep the byte-for-byte
     * `stripHtml` behaviour — this change does not touch that path.
     */
    val plainBody: String? = null,
)
