package `in`.jphe.storyvox.playback.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1786 — pure-function tests for the chapter-end decisions extracted
 * from EnginePlayer's consumer thread and advanceChapter.
 *
 * Root cause: the producer runs up to a queue's worth of chunks ahead of the
 * speaker, so on a short chapter `producedAllSentences` is true long before
 * the listener reaches the end. A teardown (Next, seek, voice swap, the next
 * chapter's loadAndPlay) closed the source, the consumer read "stream ended +
 * producedAll" as a natural end, and a spurious handleChapterDone advanced
 * past (or, on the last chapter, finished the book and PAUSED) a chapter
 * nobody finished.
 */
class EnginePlayerChapterEndDecisionTest {

    // ── classifyConsumerExit ────────────────────────────────────────────

    @Test
    fun `teardown after the producer ran ahead is a stop, not a natural end`() {
        // The #1786 case: a 6-sentence Guide, producer done, listener on
        // sentence 2 when Next / loadAndPlay tore the pipeline down.
        assertEquals(
            ConsumerExit.Stopped,
            classifyConsumerExit(
                producedAllSentences = true,
                stoppedByTeardown = true,
                chunksWritten = 3,
                lastFullyWrittenSentenceIndex = 2,
                lastSentenceIndex = 5,
            ),
        )
    }

    @Test
    fun `stream ending on its own after the last sentence is a natural end`() {
        assertEquals(
            ConsumerExit.NaturalEnd,
            classifyConsumerExit(
                producedAllSentences = true,
                stoppedByTeardown = false,
                chunksWritten = 6,
                lastFullyWrittenSentenceIndex = 5,
                lastSentenceIndex = 5,
            ),
        )
    }

    @Test
    fun `teardown during the final drain still counts as a natural end — the 573 race`() {
        // The last sentence is fully in the AudioTrack; a watchdog or the
        // next chapter's stop landed while the HW buffer drained. The chapter
        // WAS heard to the end, so the advance must still fire.
        assertEquals(
            ConsumerExit.NaturalEnd,
            classifyConsumerExit(
                producedAllSentences = true,
                stoppedByTeardown = true,
                chunksWritten = 6,
                lastFullyWrittenSentenceIndex = 5,
                lastSentenceIndex = 5,
            ),
        )
    }

    @Test
    fun `producer not finished is always a stop`() {
        assertEquals(
            ConsumerExit.Stopped,
            classifyConsumerExit(
                producedAllSentences = false,
                stoppedByTeardown = false,
                chunksWritten = 4,
                lastFullyWrittenSentenceIndex = 3,
                lastSentenceIndex = 40,
            ),
        )
    }

    @Test
    fun `zero chunks with no teardown is the 1311 silent chapter`() {
        assertEquals(
            ConsumerExit.SilentChapter,
            classifyConsumerExit(
                producedAllSentences = true,
                stoppedByTeardown = false,
                chunksWritten = 0,
                lastFullyWrittenSentenceIndex = -1,
                lastSentenceIndex = 5,
            ),
        )
    }

    @Test
    fun `zero chunks because of a teardown is a stop, not a silent-chapter error`() {
        // Next tapped before the first chunk was dequeued: no error banner.
        assertEquals(
            ConsumerExit.Stopped,
            classifyConsumerExit(
                producedAllSentences = true,
                stoppedByTeardown = true,
                chunksWritten = 0,
                lastFullyWrittenSentenceIndex = -1,
                lastSentenceIndex = 5,
            ),
        )
    }

    @Test
    fun `last sentence dequeued but only partly written before teardown is a stop`() {
        // lastFullyWrittenSentenceIndex lags: the final chunk was cut short.
        assertEquals(
            ConsumerExit.Stopped,
            classifyConsumerExit(
                producedAllSentences = true,
                stoppedByTeardown = true,
                chunksWritten = 6,
                lastFullyWrittenSentenceIndex = 4,
                lastSentenceIndex = 5,
            ),
        )
    }

    // ── shouldHandleChapterDone ─────────────────────────────────────────

    @Test
    fun `chapter-done for the chapter still playing is handled`() {
        assertTrue(shouldHandleChapterDone("f:ch1", "f:ch1"))
    }

    @Test
    fun `chapter-done arriving after the engine moved on is dropped`() {
        // Pre-fix this marked ch2 completed and advanced past it (or ended
        // the book and paused when ch2 was the last chapter).
        assertFalse(shouldHandleChapterDone("f:ch1", "f:ch2"))
    }

    @Test
    fun `chapter-done with no chapter on either side is dropped`() {
        assertFalse(shouldHandleChapterDone(null, null))
        assertFalse(shouldHandleChapterDone("f:ch1", null))
    }

    // ── shouldFinishBookWhenNoNextChapter ───────────────────────────────

    @Test
    fun `natural end of the last chapter finishes the book`() {
        assertTrue(shouldFinishBookWhenNoNextChapter(fromNaturalEnd = true, isBuffering = true))
        assertTrue(shouldFinishBookWhenNoNextChapter(fromNaturalEnd = true, isBuffering = false))
    }

    @Test
    fun `user Next on the last chapter does not finish the book`() {
        // The coordinator's on-device capture: NEXT on "Free cell service"
        // (last Guide) logged end-of-book. Now it is a no-op.
        assertFalse(shouldFinishBookWhenNoNextChapter(fromNaturalEnd = false, isBuffering = false))
    }

    @Test
    fun `watchdog recovering a stuck transition on the last chapter finishes the book`() {
        assertTrue(shouldFinishBookWhenNoNextChapter(fromNaturalEnd = false, isBuffering = true))
    }
}
