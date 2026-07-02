package `in`.jphe.storyvox.playback.briefing

import `in`.jphe.storyvox.data.briefing.BriefingBuilder
import `in`.jphe.storyvox.data.briefing.BriefingConfig
import `in`.jphe.storyvox.data.briefing.BriefingItem
import `in`.jphe.storyvox.playback.BufferTelemetry
import `in`.jphe.storyvox.playback.EngineState
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.PlaybackUiEvent
import `in`.jphe.storyvox.playback.SleepTimerMode
import `in`.jphe.storyvox.playback.WaitReason
import `in`.jphe.storyvox.playback.tts.RecapPlaybackState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1467 — behavior of the cross-fiction briefing stitcher.
 *
 * Two layers are tested: the pure [BriefingQueueController.advance] cursor
 * transition (no player needed), and the end-to-end play-through where a
 * `BookFinished` event walks the queue via the real [PlaybackController.play]
 * path.
 */
class BriefingQueueControllerTest {

    private fun item(n: Int) = BriefingItem("f$n", "c$n", "src", "Item $n")

    private fun session(count: Int, index: Int = 0) =
        BriefingSession(items = (1..count).map(::item), index = index)

    // ─── pure cursor logic ──────────────────────────────────────────────────

    @Test fun `advance moves to the next item`() {
        val next = BriefingQueueController.advance(session(count = 3, index = 0))
        assertEquals(1, next.index)
        assertFalse(next.finished)
        assertEquals("f2", next.current?.fictionId)
    }

    @Test fun `advancing off the last item finishes the briefing`() {
        val next = BriefingQueueController.advance(session(count = 3, index = 2))
        assertTrue(next.finished)
        assertNull(next.current)
        assertEquals(3, next.position) // parks at size for "3 of 3"
    }

    @Test fun `a single-item briefing finishes after one advance`() {
        val next = BriefingQueueController.advance(session(count = 1, index = 0))
        assertTrue(next.finished)
        assertNull(next.current)
    }

    @Test fun `position is 1-based while playing`() {
        assertEquals(1, session(count = 3, index = 0).position)
        assertEquals(3, session(count = 3, index = 2).position)
    }

    // ─── end-to-end play-through ──────────────────────────────────────────────

    @Test fun `start plays the first item and BookFinished walks the queue`() = runTest {
        val controller = RecordingController()
        val builder = FakeBuilder((1..3).map(::item))
        val queue = BriefingQueueController(controller, builder, backgroundScope)

        assertTrue(queue.start(BriefingConfig(emptyList())))
        runCurrent()
        assertEquals(listOf("f1" to "c1"), controller.plays)
        assertEquals(0, queue.session.value?.index)

        controller.emittableEvents.emit(PlaybackUiEvent.BookFinished)
        runCurrent()
        assertEquals(listOf("f1" to "c1", "f2" to "c2"), controller.plays)

        controller.emittableEvents.emit(PlaybackUiEvent.BookFinished)
        runCurrent()
        assertEquals(listOf("f1" to "c1", "f2" to "c2", "f3" to "c3"), controller.plays)

        // Past the last item: finishes, plays nothing more.
        controller.emittableEvents.emit(PlaybackUiEvent.BookFinished)
        runCurrent()
        assertEquals(3, controller.plays.size)
        assertTrue(queue.session.value?.finished == true)
    }

    @Test fun `start returns false and plays nothing when the build is empty`() = runTest {
        val controller = RecordingController()
        val queue = BriefingQueueController(controller, FakeBuilder(emptyList()), backgroundScope)

        assertFalse(queue.start(BriefingConfig(emptyList())))
        runCurrent()
        assertTrue(controller.plays.isEmpty())
        assertNull(queue.session.value)
    }

    // ─── test doubles ─────────────────────────────────────────────────────────

    private class FakeBuilder(private val items: List<BriefingItem>) : BriefingBuilder {
        override suspend fun build(config: BriefingConfig): List<BriefingItem> = items
    }

    /** Minimal PlaybackController that records play() calls and lets the test drive events. */
    private class RecordingController : PlaybackController {
        val plays = mutableListOf<Pair<String, String>>()
        val emittableEvents = MutableSharedFlow<PlaybackUiEvent>(extraBufferCapacity = 8)

        override suspend fun play(fictionId: String, chapterId: String, charOffset: Int) {
            plays += fictionId to chapterId
        }

        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState()).asStateFlow()
        override val events: SharedFlow<PlaybackUiEvent> = emittableEvents.asSharedFlow()
        override val engineState: StateFlow<EngineState> = MutableStateFlow<EngineState>(EngineState.Idle).asStateFlow()
        override val playbackPositionMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
        override val chapterDurationMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
        override val recapPlayback: StateFlow<RecapPlaybackState> =
            MutableStateFlow(RecapPlaybackState.Idle).asStateFlow()
        override val waitReason: StateFlow<WaitReason?> = MutableStateFlow<WaitReason?>(null).asStateFlow()

        override fun pause() = Unit
        override fun resume() = Unit
        override fun togglePlayPause() = Unit
        override fun seekTo(charOffset: Int) = Unit
        override fun seekToPositionMs(positionMs: Long) = Unit
        override fun skipForward30s() = Unit
        override fun skipBack30s() = Unit
        override fun prewarmEngine() = Unit
        override fun nextSentence() = Unit
        override fun previousSentence() = Unit
        override fun nextParagraph() = Unit
        override fun previousParagraph() = Unit
        override suspend fun nextChapter() = Unit
        override suspend fun previousChapter() = Unit
        override suspend fun jumpToChapter(chapterId: String) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setPitch(pitch: Float) = Unit
        override fun setPunctuationPauseMultiplier(multiplier: Float) = Unit
        override fun startSleepTimer(mode: SleepTimerMode) = Unit
        override fun cancelSleepTimer() = Unit
        override fun toggleSleepTimer() = Unit
        override fun setShakeToExtendEnabled(enabled: Boolean) = Unit
        override suspend fun speakText(text: String) = Unit
        override fun stopSpeaking() = Unit
        override fun bufferTelemetry() = BufferTelemetry()
        override suspend fun bookmarkHere() = Unit
        override suspend fun clearBookmark() = Unit
        override suspend fun jumpToBookmark() = false
    }
}
