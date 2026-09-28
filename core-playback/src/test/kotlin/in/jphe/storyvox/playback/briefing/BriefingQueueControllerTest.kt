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
import `in`.jphe.storyvox.playback.diagnostics.WaitReason
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
import org.junit.Assert.assertNotNull
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

    @Test fun `start plays the first item and ChapterDone walks the queue per item`() = runTest {
        val controller = RecordingController()
        val builder = FakeBuilder((1..3).map(::item))
        val queue = BriefingQueueController(controller, builder, backgroundScope)

        assertTrue(queue.start(BriefingConfig(emptyList())))
        runCurrent()
        assertEquals(listOf("f1" to "c1"), controller.plays)
        assertEquals(0, queue.session.value?.index)

        // A ChapterDone for some other chapter (the engine's own in-fiction
        // advance) and a BookFinished are both ignored in per-item mode.
        controller.emittableEvents.emit(PlaybackUiEvent.ChapterDone("other"))
        controller.emittableEvents.emit(PlaybackUiEvent.BookFinished)
        runCurrent()
        assertEquals(1, controller.plays.size)

        controller.emittableEvents.emit(PlaybackUiEvent.ChapterDone("c1"))
        runCurrent()
        assertEquals(listOf("f1" to "c1", "f2" to "c2"), controller.plays)

        controller.emittableEvents.emit(PlaybackUiEvent.ChapterDone("c2"))
        runCurrent()
        assertEquals(listOf("f1" to "c1", "f2" to "c2", "f3" to "c3"), controller.plays)

        // Past the last item: finishes, plays nothing more.
        controller.emittableEvents.emit(PlaybackUiEvent.ChapterDone("c3"))
        runCurrent()
        assertEquals(3, controller.plays.size)
        assertTrue(queue.session.value?.finished == true)
    }

    @Test fun `startWith in BookFinished mode advances per fiction`() = runTest {
        val controller = RecordingController()
        val queue = BriefingQueueController(controller, FakeBuilder(emptyList()), backgroundScope)

        assertTrue(queue.startWith((1..2).map(::item)))
        runCurrent()
        controller.emittableEvents.emit(PlaybackUiEvent.ChapterDone("c1"))
        runCurrent()
        assertEquals(listOf("f1" to "c1"), controller.plays)
        controller.emittableEvents.emit(PlaybackUiEvent.BookFinished)
        runCurrent()
        assertEquals(listOf("f1" to "c1", "f2" to "c2"), controller.plays)
    }

    @Test fun `shouldAdvance ignores events once finished`() {
        val done = session(count = 1).copy(index = 1, finished = true)
        assertFalse(BriefingQueueController.shouldAdvance(PlaybackUiEvent.ChapterDone("c1"), done, true))
        assertFalse(BriefingQueueController.shouldAdvance(PlaybackUiEvent.BookFinished, null, false))
    }

    @Test fun `start returns false and plays nothing when the build is empty`() = runTest {
        val controller = RecordingController()
        val queue = BriefingQueueController(controller, FakeBuilder(emptyList()), backgroundScope)

        assertFalse(queue.start(BriefingConfig(emptyList())))
        runCurrent()
        assertTrue(controller.plays.isEmpty())
        assertNull(queue.session.value)
    }

    @Test fun `startWith plays a prebuilt queue from the requested index`() = runTest {
        val controller = RecordingController()
        val queue = BriefingQueueController(controller, FakeBuilder(emptyList()), backgroundScope)

        assertTrue(queue.startWith((1..3).map(::item), startIndex = 1))
        runCurrent()
        assertEquals(listOf("f2" to "c2"), controller.plays)

        controller.emittableEvents.emit(PlaybackUiEvent.BookFinished)
        runCurrent()
        assertEquals(listOf("f2" to "c2", "f3" to "c3"), controller.plays)
    }

    @Test fun `startWith clamps an out-of-range index and rejects an empty queue`() = runTest {
        val controller = RecordingController()
        val queue = BriefingQueueController(controller, FakeBuilder(emptyList()), backgroundScope)

        assertFalse(queue.startWith(emptyList()))
        assertTrue(queue.startWith((1..2).map(::item), startIndex = 99))
        runCurrent()
        assertEquals(listOf("f2" to "c2"), controller.plays)
    }

    // ─── process death (#1467) ────────────────────────────────────────────────

    @Test fun `a briefing survives process death and resumes advancing`() = runTest {
        val store = FakeSessionStore()
        val first = RecordingController()
        val before = BriefingQueueController(first, FakeBuilder((1..3).map(::item)), backgroundScope, store, clock = { 1_000L })
        assertTrue(before.start(BriefingConfig(emptyList())))
        runCurrent()
        first.emittableEvents.emit(PlaybackUiEvent.ChapterDone("c1"))
        runCurrent()
        assertEquals(1, before.session.value?.index)

        // The process dies; a fresh controller over the same store takes over.
        val second = RecordingController()
        val after = BriefingQueueController(second, FakeBuilder(emptyList()), backgroundScope, store, clock = { 2_000L })
        assertTrue("the saved session is restored", after.restore())
        runCurrent()
        assertEquals(1, after.session.value?.index)
        assertEquals("c2", after.session.value?.current?.chapterId)
        assertTrue("restore must never start audio by itself", second.plays.isEmpty())

        // The current item ending still walks the queue, in the saved mode.
        second.emittableEvents.emit(PlaybackUiEvent.BookFinished)
        runCurrent()
        assertTrue("per-item mode survives the restore", second.plays.isEmpty())
        second.emittableEvents.emit(PlaybackUiEvent.ChapterDone("c2"))
        runCurrent()
        assertEquals(listOf("f3" to "c3"), second.plays)
    }

    @Test fun `a stale saved briefing is dropped instead of restored`() = runTest {
        val store = FakeSessionStore()
        val before = BriefingQueueController(RecordingController(), FakeBuilder(emptyList()), backgroundScope, store, clock = { 0L })
        assertTrue(before.startWith((1..2).map(::item)))
        runCurrent()
        assertNotNull("the session was saved, so dropping it is a real decision", store.saved)

        val later = BriefingQueueController.STALE_AFTER_MS + 1
        val after = BriefingQueueController(RecordingController(), FakeBuilder(emptyList()), backgroundScope, store, clock = { later })
        assertFalse(after.restore())
        runCurrent()
        assertNull(after.session.value)
        assertNull("a stale session is cleared from the store", store.saved)
    }

    @Test fun `stopping or finishing a briefing clears the saved session`() = runTest {
        val store = FakeSessionStore()
        val controller = RecordingController()
        val queue = BriefingQueueController(controller, FakeBuilder(emptyList()), backgroundScope, store, clock = { 0L })

        assertTrue(queue.startWith((1..2).map(::item)))
        runCurrent()
        assertEquals(0, store.saved?.index)
        queue.stop()
        runCurrent()
        assertNull("stop clears it", store.saved)

        assertTrue(queue.startWith((1..1).map(::item)))
        runCurrent()
        controller.emittableEvents.emit(PlaybackUiEvent.BookFinished)
        runCurrent()
        assertTrue(queue.session.value?.finished == true)
        assertNull("finishing clears it", store.saved)
    }

    // ─── test doubles ─────────────────────────────────────────────────────────

    private class FakeSessionStore : BriefingSessionStore {
        var saved: SavedBriefingSession? = null
        override suspend fun saveSession(saved: SavedBriefingSession?) { this.saved = saved }
        override suspend fun loadSession(): SavedBriefingSession? = saved
    }

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
