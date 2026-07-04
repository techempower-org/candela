package `in`.jphe.storyvox.source.handbook

/**
 * Read seam for the Candela Handbook's bundled content.
 *
 * Mirrors :source-ocr's `OcrConfig` seam: the source module declares an
 * interface, [CandelaHandbookSource] depends on it, and unit tests supply a
 * pure-JVM fake (no Android Context, no assets) so the source's mapping logic
 * is testable without Robolectric. The real, Context-backed implementation is
 * [AssetCandelaHandbookReader], bound in [di.HandbookReaderModule].
 *
 * dx note (#1526 dogfood): `new-source.sh --local` scaffolds a *concrete*
 * `@Inject` Reader with a no-arg constructor, but the cited reference
 * (source-ocr) and any Context-backed local read need an interface + a bound
 * impl — a concrete Context-taking class can't be fake-subclassed in a pure-JVM
 * test. Reshaped to the interface form here; see the filed dx issue.
 */
internal interface CandelaHandbookReader {
    /** Handbook table-of-contents + the docs-snapshot version. */
    suspend fun manifest(): HandbookManifest

    /** One chapter's narration-ready plain-text body by id, or null if missing. */
    suspend fun chapterText(id: String): String?
}

/** Handbook table-of-contents, parsed from `assets/handbook/manifest.tsv`. */
internal data class HandbookManifest(
    val version: String,
    val chapters: List<HandbookChapter>,
)

/** One handbook section, in reading order. */
internal data class HandbookChapter(
    val id: String,
    val title: String,
    val index: Int,
)
