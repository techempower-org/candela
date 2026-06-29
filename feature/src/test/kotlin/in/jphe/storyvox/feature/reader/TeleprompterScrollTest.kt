package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1239 — Teleprompter / solo-rehearsal mode. The pace math is
 * extracted to pure functions so the per-frame auto-scroll's behaviour is
 * pinned without a Compose/Android runtime (mirrors [FocusScrollTargetTest]).
 *
 * [teleprompterScrollDeltaPx] is the heart of it: how far to advance the
 * scroll each frame at a given words-per-minute, derived from the chapter's
 * word density and the real elapsed time, so the pace is frame-rate
 * independent (identical on 60Hz and 120Hz panels). [countWords] feeds it
 * the density; [adjustTeleprompterWpm] is the −/+ stepper's clamp.
 */
class TeleprompterScrollTest {

    // ===== countWords =====

    @Test
    fun `counts whitespace-delimited words`() {
        assertEquals(2, countWords("hello world"))
        assertEquals(1, countWords("solo"))
        // Mixed/collapsed whitespace (spaces, tabs, newlines) counts as one
        // delimiter run — no empty tokens.
        assertEquals(4, countWords("one  two\tthree\nfour"))
    }

    @Test
    fun `blank or empty text has no words`() {
        assertEquals(0, countWords(""))
        assertEquals(0, countWords("   \t\n  "))
    }

    // ===== teleprompterScrollDeltaPx =====

    @Test
    fun `one second at 60 wpm advances one word of pixels`() {
        // 600 words over 6000px → 10px per word. 60 wpm = 1 word/sec, so one
        // full second (1e9 ns) should advance exactly 10px.
        val px = teleprompterScrollDeltaPx(
            wpm = 60,
            totalWords = 600,
            scrollableHeightPx = 6_000,
            elapsedNanos = 1_000_000_000L,
        )
        assertEquals(10f, px, 0.001f)
    }

    @Test
    fun `doubling wpm doubles the per-frame distance`() {
        // Same geometry; 120 wpm = 2 words/sec → 20px in one second. Confirms
        // the rate scales linearly with wpm (not a step/threshold).
        val px = teleprompterScrollDeltaPx(
            wpm = 120,
            totalWords = 600,
            scrollableHeightPx = 6_000,
            elapsedNanos = 1_000_000_000L,
        )
        assertEquals(20f, px, 0.001f)
    }

    @Test
    fun `half a frame advances half the distance`() {
        // Frame-rate independence: half the elapsed time → half the pixels.
        val px = teleprompterScrollDeltaPx(
            wpm = 60,
            totalWords = 600,
            scrollableHeightPx = 6_000,
            elapsedNanos = 500_000_000L,
        )
        assertEquals(5f, px, 0.001f)
    }

    @Test
    fun `degenerate inputs yield no movement`() {
        // Each guard independently zeroes the delta so the per-frame loop is
        // a safe no-op before layout settles (maxValue 0) or for empty text.
        assertEquals(0f, teleprompterScrollDeltaPx(0, 600, 6_000, 1_000_000_000L), 0f)
        assertEquals(0f, teleprompterScrollDeltaPx(130, 0, 6_000, 1_000_000_000L), 0f)
        assertEquals(0f, teleprompterScrollDeltaPx(130, 600, 0, 1_000_000_000L), 0f)
        assertEquals(0f, teleprompterScrollDeltaPx(130, 600, 6_000, 0L), 0f)
    }

    // ===== adjustTeleprompterWpm =====

    @Test
    fun `stepping adjusts by one wpm step`() {
        assertEquals(140, adjustTeleprompterWpm(130, 1))
        assertEquals(120, adjustTeleprompterWpm(130, -1))
    }

    @Test
    fun `stepping clamps to the supported band`() {
        assertEquals(TELEPROMPTER_MIN_WPM, adjustTeleprompterWpm(TELEPROMPTER_MIN_WPM, -1))
        assertEquals(TELEPROMPTER_MAX_WPM, adjustTeleprompterWpm(TELEPROMPTER_MAX_WPM, 1))
        // A big jump still lands on the ceiling, never past it.
        assertEquals(TELEPROMPTER_MAX_WPM, adjustTeleprompterWpm(130, 100))
    }

    @Test
    fun `default pace sits inside the supported band`() {
        assertTrue(TELEPROMPTER_DEFAULT_WPM in TELEPROMPTER_MIN_WPM..TELEPROMPTER_MAX_WPM)
    }
}
