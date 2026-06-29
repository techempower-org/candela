package `in`.jphe.storyvox.playback.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1262 — pure coroutine-test for [awaitChapterBodyReady], the
 * body-wait-with-retry that lets auto-advance recover a next chapter whose
 * body isn't cached yet (its #558 prefetch download was killed mid-flight
 * and left the row stuck at DOWNLOADING).
 *
 * Pre-fix `advanceChapter` waited ONE fixed window then surfaced a terminal
 * error, so a stuck chapter was skipped on auto-advance forever — yet a
 * later manual tap (finding the by-then-completed body) played it fine. The
 * decisive test below pins the fix: a body that only lands AFTER a re-queue
 * is now recovered, and every attempt re-queues a fresh download worker.
 *
 * Stays at the pure-function layer; the full EnginePlayer needs a Hilt graph
 * + sherpa-onnx AARs (same constraint as EnginePlayerAutoPlayDecisionTest).
 * runTest skips the (virtual) timeout delays, so the window values below
 * only need to preserve first-vs-retry ordering, not the production 60s/20s.
 */
class ChapterBodyWaitRetryTest {

    private val attempts = 2
    private val firstMs = 60_000L
    private val retryMs = 20_000L

    @Test
    fun `returns true immediately when the body is already present`() = runTest {
        val present = MutableStateFlow(true)
        var queueCalls = 0
        val ready = awaitChapterBodyReady(
            attempts = attempts,
            firstTimeoutMs = firstMs,
            retryTimeoutMs = retryMs,
            queueDownload = { queueCalls++ },
            observeBodyPresent = { present },
        )
        assertTrue("body present on the first window → ready", ready)
        assertEquals("queued exactly once — no retry needed", 1, queueCalls)
    }

    @Test
    fun `recovers a chapter whose body only lands after a re-queue (#1262)`() = runTest {
        // Body absent on attempt 1; the SECOND queueDownload — the fresh
        // REPLACE worker that restarts the killed/stuck download — is what
        // makes it land. Pre-fix's single window would have surfaced the
        // terminal error here and skipped the chapter forever.
        val present = MutableStateFlow(false)
        var queueCalls = 0
        val ready = awaitChapterBodyReady(
            attempts = attempts,
            firstTimeoutMs = firstMs,
            retryTimeoutMs = retryMs,
            queueDownload = {
                queueCalls++
                if (queueCalls == 2) present.value = true
            },
            observeBodyPresent = { present },
        )
        assertTrue("body landed after the re-queue → recovered, not skipped", ready)
        assertEquals("re-queued a fresh worker on the retry", 2, queueCalls)
    }

    @Test
    fun `returns false after exhausting all attempts when the body never lands`() = runTest {
        val present = MutableStateFlow(false)
        var queueCalls = 0
        val ready = awaitChapterBodyReady(
            attempts = attempts,
            firstTimeoutMs = firstMs,
            retryTimeoutMs = retryMs,
            queueDownload = { queueCalls++ },
            observeBodyPresent = { present },
        )
        assertFalse("body never landed → caller surfaces the terminal error", ready)
        assertEquals("tried every window, re-queuing each time", attempts, queueCalls)
    }

    @Test
    fun `a single attempt behaves like the pre-fix single-shot wait`() = runTest {
        // attempts = 1 collapses to the old one-window behaviour: one queue,
        // and a body that never lands in that window returns false.
        val present = MutableStateFlow(false)
        var queueCalls = 0
        val ready = awaitChapterBodyReady(
            attempts = 1,
            firstTimeoutMs = firstMs,
            retryTimeoutMs = retryMs,
            queueDownload = { queueCalls++ },
            observeBodyPresent = { present },
        )
        assertFalse(ready)
        assertEquals("no retry when attempts == 1", 1, queueCalls)
    }
}
