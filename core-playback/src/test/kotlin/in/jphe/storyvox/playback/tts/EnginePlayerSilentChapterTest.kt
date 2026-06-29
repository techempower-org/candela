package `in`.jphe.storyvox.playback.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1311 — pure-function unit test for [isSilentNaturalEnd], the rule
 * that stops a chapter which synthesised to ZERO audio from being silently
 * auto-advanced past.
 *
 * Background: the producer (EngineStreamingSource) `?: continue`s past any
 * sentence whose `generateAudioPCM` returns null, then still sets
 * `producedAllSentences = true` once it has walked every sentence. If EVERY
 * sentence comes back null, the consumer reads a "natural end" having
 * dequeued no chunks. Pre-#1311 that fed handleChapterDone → advanceChapter
 * and the chapter was silently skipped (the synth-side sibling of the #1262
 * watchdog skip). The consumer now counts dequeued chunks and, at a natural
 * end with zero of them, surfaces a retryable error instead of advancing.
 *
 * Like its #728 / #980 / #642 neighbours, the decision is extracted to a
 * top-level function so it can be pinned here without standing up the full
 * EnginePlayer + Hilt + sherpa-onnx graph.
 */
class EnginePlayerSilentChapterTest {

    @Test
    fun `natural end with zero chunks is a silent chapter — the #1311 case`() {
        // producedAllSentences flipped true but the producer enqueued no
        // chunk (every sentence's generateAudioPCM returned null). This is
        // exactly the state that pre-#1311 silently skipped the chapter.
        assertTrue(
            "a natural end that produced no audio must be treated as silent (#1311)",
            isSilentNaturalEnd(naturalEnd = true, chunksEmitted = 0),
        )
    }

    @Test
    fun `natural end with audio is a normal completion — not silent`() {
        // The overwhelmingly common path: the chapter played, chunks were
        // emitted, the producer finished. Must NOT trip the guard — that
        // would block legitimate auto-advance at every chapter boundary.
        assertFalse(
            "a natural end that produced audio is a normal completion",
            isSilentNaturalEnd(naturalEnd = true, chunksEmitted = 137),
        )
    }

    @Test
    fun `a single emitted chunk is enough to count as not-silent`() {
        // Boundary: one chunk means the engine produced real audio, so the
        // chapter is not silent even if it ended early for another reason.
        assertFalse(isSilentNaturalEnd(naturalEnd = true, chunksEmitted = 1))
    }

    @Test
    fun `a non-natural exit with zero chunks is NOT a silent chapter`() {
        // User paused / seeked / swapped voice / hit book-end before any
        // audio played: naturalEnd is false (producedAllSentences never
        // set). That's an intentional stop, not a silent skip — the guard
        // must not fire and surface a spurious error.
        assertFalse(
            "a non-natural exit before audio is not the #1311 silent-skip case",
            isSilentNaturalEnd(naturalEnd = false, chunksEmitted = 0),
        )
    }

    @Test
    fun `a non-natural exit mid-chapter is NOT a silent chapter`() {
        // Paused/stopped partway through a chapter that WAS producing audio.
        // Not a natural end, so not the guard's concern.
        assertFalse(isSilentNaturalEnd(naturalEnd = false, chunksEmitted = 42))
    }
}
