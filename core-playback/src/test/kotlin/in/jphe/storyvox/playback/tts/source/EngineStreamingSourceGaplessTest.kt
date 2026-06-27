package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.tts.Sentence
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #573 — Gapless auto-advance: tests for the new
 * [PcmSource.producedAllSentences] contract.
 *
 * The flag exists to give the consumer thread an unambiguous signal
 * for "this is a natural chapter end" that doesn't race against
 * `pipelineRunning` flips from a concurrent stopPlaybackPipeline.
 *
 * Pre-#573 the consumer inferred natural-end from
 * `pipelineRunning.get()` captured at END_PILL dequeue time, then
 * re-checked it ~100 ms later in the finally block — opening a race
 * window that silently swallowed handleChapterDone on Notion sources
 * (the on-device symptom that drove this fix; see PR thread).
 *
 * These tests pin the contract:
 *  1. Before any nextChunk, flag is false.
 *  2. After producer drains all sentences and consumer dequeues
 *     null, flag is true. (Natural end.)
 *  3. After close() (simulating stopPlaybackPipeline), null returns
 *     without setting the flag. (Cancellation, NOT a chapter end.)
 *
 * The integration with EnginePlayer's finally-block gating is tested
 * indirectly: with the flag wired into the gate (see
 * EnginePlayer.startPlaybackPipeline's consumer finally), point 2
 * fires handleChapterDone, point 3 does not.
 */
class EngineStreamingSourceGaplessTest {

    @Test
    fun `producedAllSentences is false before any chunk is drained`() = runBlocking {
        val sentences = listOf(
            Sentence(0, 0, 10, "One."),
            Sentence(1, 11, 20, "Two."),
        )
        val src = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = GaplessFakeVoiceEngine(22050) { ByteArray(100) },
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
        )

        assertFalse(
            "producedAllSentences must be false until the producer reaches end-of-list",
            src.producedAllSentences,
        )

        src.close()
    }

    @Test
    fun `producedAllSentences flips true when consumer drains to null on natural end`() =
        runBlocking {
            val sentences = listOf(
                Sentence(0, 0, 10, "One."),
                Sentence(1, 11, 20, "Two."),
                Sentence(2, 21, 30, "Three."),
            )
            val src = EngineStreamingSource(
                sentences = sentences,
                startSentenceIndex = 0,
                engine = GaplessFakeVoiceEngine(22050) { ByteArray(50) },
                speed = 1f,
                pitch = 1f,
                engineMutex = Mutex(),
            )

            // Drain every sentence, then the END_PILL.
            assertNotNull(src.nextChunk())
            assertNotNull(src.nextChunk())
            assertNotNull(src.nextChunk())
            val end = src.nextChunk()
            assertNull("End-of-stream chunk must be null", end)

            assertTrue(
                "producedAllSentences must be true after natural END_PILL",
                src.producedAllSentences,
            )

            src.close()
        }

    @Test
    fun `producedAllSentences stays false when close races a partially-drained source`() =
        runBlocking {
            // #1166 — deterministic gating. The prior version pulled one
            // chunk, called close(), and *assumed* the producer was still
            // mid-stream. With an instant fake engine that assumption is a
            // race: after close() clears the bounded queue (freeing the
            // back-pressure slot) there is a window before the cancellation
            // is actually delivered, and an instant producer can blast
            // through the remaining sentences in it — either reaching the
            // natural-end branch (producedAll=true) or slipping a stray
            // chunk ahead of the END_PILL so the second nextChunk() returns
            // non-null. Both are test races, not contract violations, and
            // they made this test flaky.
            //
            // Fix: gate the engine. Sentence 0 synthesizes instantly; every
            // later sentence parks inside generateAudioPCM until close()'s
            // producerExecutor.shutdownNow() interrupts the worker. That
            // turns "the producer is mid-stream, NOT at natural end, when
            // close() lands" into a guarantee instead of a hope.
            val sentences = (0 until 50).map {
                Sentence(it, it * 10, it * 10 + 8, "Sentence $it.")
            }
            // Counts down the instant the producer enters the synth for
            // sentence 1, so the test can wait until the producer is
            // provably parked before racing close() against it.
            val reachedGate = CountDownLatch(1)
            // Never counted down by the test — the parked worker leaves the
            // await only when close()'s executor shutdown interrupts it.
            val gate = CountDownLatch(1)
            val calls = AtomicInteger(0)
            val src = EngineStreamingSource(
                sentences = sentences,
                startSentenceIndex = 0,
                engine = GaplessFakeVoiceEngine(22050) {
                    if (calls.getAndIncrement() >= 1) {
                        reachedGate.countDown()
                        try {
                            gate.await()
                        } catch (_: InterruptedException) {
                            // close() → producerExecutor.shutdownNow()
                            // interrupted the parked worker. Swallow it and
                            // return normally: the producer's
                            // `if (!running.get()) return@launch` (running was
                            // set false at the top of close()) then stops the
                            // loop BEFORE this sentence is enqueued — the same
                            // clean exit a real engine's cancelled synth
                            // takes. producedAll is never set and no stray
                            // chunk reaches the queue.
                        }
                    }
                    ByteArray(50)
                },
                speed = 1f,
                pitch = 1f,
                engineMutex = Mutex(),
            )

            // Drain sentence 0, then block until the producer is provably
            // parked mid-synth on sentence 1. close() now cannot lose a race
            // to natural end — the producer physically cannot advance past
            // the gate.
            assertNotNull(src.nextChunk())
            assertTrue(
                "producer should have parked inside the gated synth",
                reachedGate.await(5, TimeUnit.SECONDS),
            )

            // Mid-stream, pre-close: the flag must already be false.
            assertFalse(
                "producedAllSentences must be false while the producer is parked mid-stream",
                src.producedAllSentences,
            )

            // Close mid-stream — same path as stopPlaybackPipeline.close.
            // The executor shutdown interrupts the gated worker.
            src.close()

            // close() cleared the (empty) queue and pushed END_PILL, so the
            // next dequeue is the pill, not a stray chunk. The flag must
            // remain false because the producer was CANCELLED, not naturally
            // exhausted — the post-#573 EnginePlayer gate (`naturalEnd =
            // source.producedAllSentences`) therefore correctly skips
            // handleChapterDone for this case (user-initiated stop, not a
            // chapter end).
            val end = src.nextChunk()
            assertNull(end)

            assertFalse(
                "producedAllSentences must NOT be true after close mid-stream — " +
                    "this would falsely fire chapter-done on a user pause / voice swap",
                src.producedAllSentences,
            )
        }

    @Test
    fun `producedAllSentences flips true even on a single-sentence chapter`() =
        runBlocking {
            // Edge case: chapter with exactly one sentence. The natural-end
            // path still has to fire — pre-#573 a one-sentence chapter
            // (e.g. Royal Road short chapters) exited the producer's
            // for-loop after one iteration and hit the END_PILL push
            // immediately, but the consumer-thread `naturalEnd =
            // pipelineRunning.get()` could observe a transient false if
            // the user tapped pause in the same millisecond. The
            // producer-set flag is monotonic — once we exited the loop
            // naturally, the flag is sticky-true.
            val sentences = listOf(Sentence(0, 0, 10, "Solo."))
            val src = EngineStreamingSource(
                sentences = sentences,
                startSentenceIndex = 0,
                engine = GaplessFakeVoiceEngine(22050) { ByteArray(50) },
                speed = 1f,
                pitch = 1f,
                engineMutex = Mutex(),
            )

            assertNotNull(src.nextChunk())
            assertNull(src.nextChunk())

            assertTrue(src.producedAllSentences)

            src.close()
        }

    @Test
    fun `producedAllSentences flips true on zero-sentence chapter via empty list`() =
        runBlocking {
            // Issue #442 edge: chunker yields zero sentences. EnginePlayer
            // bails before constructing the source for this case (with
            // a typed error), so this is mostly defensive — but if a
            // future code path ever constructs an empty source, the
            // natural-end path should still fire cleanly with no chunks.
            val src = EngineStreamingSource(
                sentences = emptyList(),
                startSentenceIndex = 0,
                engine = GaplessFakeVoiceEngine(22050) { ByteArray(50) },
                speed = 1f,
                pitch = 1f,
                engineMutex = Mutex(),
            )

            // First chunk is immediately null (producer loop iterated
            // zero times, then pushed END_PILL).
            assertNull(src.nextChunk())
            assertTrue(
                "Zero-sentence source must still report natural end so the consumer " +
                    "advances past the empty chapter rather than stalling",
                src.producedAllSentences,
            )

            src.close()
        }
}

/** Local copy of the test-only engine handle. The sibling
 *  EngineStreamingSourceTest declares the same shape `private`; this
 *  inlined copy keeps both test classes independent so a change to one
 *  test's fake doesn't ripple into the other. Identity-only behaviour. */
private class GaplessFakeVoiceEngine(
    override val sampleRate: Int,
    val pcmFor: (String) -> ByteArray,
) : EngineStreamingSource.VoiceEngineHandle {
    override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? = pcmFor(text)
}
