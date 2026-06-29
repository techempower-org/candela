package `in`.jphe.storyvox.playback.tts

import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1330 — regression test for [playingFictionId], the rule that the
 * fiction id the player exposes while playing comes from the loaded chapter
 * (FK-bound to a real `fiction` row), not the caller's possibly-non-canonical
 * request.
 *
 * The bug: a book whose playback was started with a Royal Road URL *slug*
 * ("archmage-coefficient") instead of its numeric id ("156215") played fine —
 * the chapter loads by chapterId — but leaked the slug into `currentFictionId`,
 * so the reader's "Open chapter list" → FictionDetail lookup 404'd ("Fiction
 * archmage-coefficient not found") for a book that was playing. Pinning the
 * pure rule guards the fix without standing up the full EnginePlayer graph
 * (same approach as [EnginePlayerSilentChapterTest]).
 */
class EnginePlayerFictionIdTest {

    private fun chapter(fictionId: String) = PlaybackChapter(
        id = "3132975",
        fictionId = fictionId,
        text = "Once upon a time…",
        title = "Chapter 1",
        bookTitle = "The Archmage Coefficient",
        coverUrl = null,
    )

    @Test
    fun `chapter id wins over a non-canonical requested id — the #1330 case`() {
        // Started with the URL slug; the chapter's FK-bound numeric id is real.
        assertEquals(
            "156215",
            playingFictionId(requestedFictionId = "archmage-coefficient", chapter = chapter("156215")),
        )
    }

    @Test
    fun `a canonical requested id is unchanged (no-op for correct callers)`() {
        assertEquals(
            "156215",
            playingFictionId(requestedFictionId = "156215", chapter = chapter("156215")),
        )
    }

    @Test
    fun `a prefixed import id round-trips when the chapter agrees`() {
        // EPUB/PDF imports use `<source>:<hash>` ids; the chapter carries the
        // same id, so the helper returns it unchanged (no prefix stripping).
        assertEquals(
            "epub:abc123",
            playingFictionId(requestedFictionId = "epub:abc123", chapter = chapter("epub:abc123")),
        )
    }

    @Test
    fun `falls back to the requested id only when the chapter id is blank`() {
        // The fictionId column is NOT NULL, so this shouldn't happen — but the
        // fallback guarantees we never regress currentFictionId to blank.
        assertEquals(
            "156215",
            playingFictionId(requestedFictionId = "156215", chapter = chapter("")),
        )
    }
}
