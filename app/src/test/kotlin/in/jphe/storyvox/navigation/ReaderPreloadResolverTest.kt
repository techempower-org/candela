package `in`.jphe.storyvox.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1455 — the deep-link handler must LOAD a brand-new chapter into the
 * PlaybackController before opening the reader (the reader is a passive view
 * of the controller; navigating alone hangs it on "loading chapter"). Three
 * emitters share the `storyvox.open_reader.*` extras — the playback
 * notification and now-playing widget (already-playing chapter → navigate
 * only) and the new-chapter notification (brand-new chapter → preload). The
 * discriminator is [DeepLinkResolver.EXTRA_OPEN_READER_PRELOAD]; the pure
 * decision below is what MainActivity keys on.
 *
 * Pure (no Android types), so it runs as a plain JUnit test — no Robolectric,
 * mirroring [DocumentImportClassifierTest].
 */
class ReaderPreloadResolverTest {

    private fun decide(fid: String?, cid: String?, requested: Boolean) =
        DeepLinkResolver.shouldPreloadReader(fid, cid, requested)

    // ── Preload requested (the new-chapter notification) ──────────────

    @Test
    fun preloadFlag_withBothIds_returnsTarget() {
        assertEquals(
            DeepLinkResolver.ReaderPreload("royalroad:12345", "royalroad:12345:67"),
            decide("royalroad:12345", "royalroad:12345:67", requested = true),
        )
    }

    // ── Preload NOT requested (playback notification / now-playing widget) ─

    @Test
    fun noPreloadFlag_withBothIds_isNull() {
        // The playback notification + widget carry the ids for the
        // already-playing chapter but must stay navigate-only — preloading
        // there (autoPlay=false) would pause the audio in progress.
        assertNull(decide("royalroad:12345", "royalroad:12345:67", requested = false))
    }

    // ── Preload requested but ids incomplete → refuse (no half-load) ──

    @Test
    fun preloadFlag_withMissingFictionId_isNull() {
        assertNull(decide(null, "royalroad:12345:67", requested = true))
        assertNull(decide("", "royalroad:12345:67", requested = true))
        assertNull(decide("   ", "royalroad:12345:67", requested = true))
    }

    @Test
    fun preloadFlag_withMissingChapterId_isNull() {
        assertNull(decide("royalroad:12345", null, requested = true))
        assertNull(decide("royalroad:12345", "", requested = true))
        assertNull(decide("royalroad:12345", "   ", requested = true))
    }

    @Test
    fun preloadFlag_withBothIdsMissing_isNull() {
        assertNull(decide(null, null, requested = true))
        assertNull(decide("", "", requested = true))
    }

    // ── No flag AND no ids (a non-reader deep link) → null ────────────

    @Test
    fun nothingToGoOn_isNull() {
        assertNull(decide(null, null, requested = false))
    }
}
