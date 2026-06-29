package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1298 — source routing for a fictionId with no persisted row yet.
 *
 * Regression: importing an EPUB (or PDF) and opening it navigates straight
 * to FictionDetail before any row is written, so `refreshDetail` resolved
 * the source from the absent row and defaulted to Royal Road — sending
 * `epub:<hash>` to RoyalRoadSource, which answered "Fiction epub:<hash> not
 * found". The fix derives the source from the id's `<sourceId>:` prefix.
 */
class SourceIdForFictionIdTest {

    private val bound = setOf(
        SourceIds.ROYAL_ROAD, SourceIds.EPUB, SourceIds.PDF,
        SourceIds.AO3, SourceIds.GUTENBERG, SourceIds.OCR,
    )

    @Test
    fun epubImportId_routesToEpub_notRoyalRoad() {
        // The exact #1298 repro (id from the bug screenshot shape).
        assertEquals(SourceIds.EPUB, sourceIdForFictionId("epub:9469520f", bound))
    }

    @Test
    fun pdfImportId_routesToPdf() {
        assertEquals(SourceIds.PDF, sourceIdForFictionId("pdf:abc12345", bound))
    }

    @Test
    fun bareNumericId_routesToRoyalRoad() {
        // Royal Road uses bare numeric ids (no prefix) — unchanged.
        assertEquals(SourceIds.ROYAL_ROAD, sourceIdForFictionId("123456", bound))
    }

    @Test
    fun otherPrefixedSources_routeToTheirSource() {
        assertEquals(SourceIds.AO3, sourceIdForFictionId("ao3:42", bound))
        assertEquals(SourceIds.GUTENBERG, sourceIdForFictionId("gutenberg:84", bound))
        assertEquals(SourceIds.OCR, sourceIdForFictionId("ocr:deadbeef", bound))
    }

    @Test
    fun multiColonId_usesFirstSegment() {
        // EPUB direct-download variant is `epub:url:<hash>`.
        assertEquals(SourceIds.EPUB, sourceIdForFictionId("epub:url:deadbeef", bound))
    }

    @Test
    fun unboundPrefix_fallsBackToRoyalRoad() {
        // Must NOT return a prefix that isn't bound — sourceFor() errors on
        // an unknown source id. Fall back to Royal Road instead.
        assertEquals(SourceIds.ROYAL_ROAD, sourceIdForFictionId("mystery:1", bound))
    }

    @Test
    fun emptyId_routesToRoyalRoad() {
        assertEquals(SourceIds.ROYAL_ROAD, sourceIdForFictionId("", bound))
    }
}
