package `in`.jphe.storyvox.playback.briefing

import `in`.jphe.storyvox.data.briefing.BriefingBuilder
import `in`.jphe.storyvox.data.briefing.BriefingConfig
import `in`.jphe.storyvox.data.briefing.BriefingItem
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.PlaybackUiEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The morning-briefing "stitcher" (#1467) — the piece that was missing.
 *
 * `PlaybackController` auto-advances **within** a fiction and then emits
 * [PlaybackUiEvent.BookFinished] at the end; it has no concept of a queue that
 * spans *different* fictions/sources. This controller adds exactly that layer,
 * and nothing more: it holds an ordered [BriefingItem] queue, plays the current
 * item through the real load path ([BriefingItemPlayer]: service, download,
 * then [PlaybackController.play]; never bare navigation, cf. the #1455 class of
 * "opened without loading" bugs), listens
 * for `BookFinished`, and advances to the next item. It never reaches into
 * `EnginePlayer`.
 *
 * Scoped as a **`@Singleton`** alongside `PlaybackController`, not to any
 * ViewModel/composable: a hands-free briefing must keep advancing when the user
 * navigates away from whatever screen started it.
 *
 * The queue and cursor also survive **process death**: every start, advance,
 * finish and stop is written to [sessionStore], and the Hilt instance restores
 * it on construction (see [restore]).
 */
@Singleton
class BriefingQueueController(
    private val controller: PlaybackController,
    private val builder: BriefingBuilder,
    private val scope: CoroutineScope,
    private val sessionStore: BriefingSessionStore = BriefingSessionStore.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val itemPlayer: BriefingItemPlayer = BriefingItemPlayer { f, c -> controller.play(f, c) },
) {
    /**
     * Hilt entry point. The real app gets a long-lived Default-dispatcher scope
     * that outlives every screen, persists through [BriefingSettingsStore], and
     * restores a briefing that a process death interrupted. Tests use the
     * primary constructor to inject a controllable scope and store.
     */
    @Inject
    constructor(
        controller: PlaybackController,
        builder: BriefingBuilder,
        sessionStore: BriefingSettingsStore,
        itemPlayer: BriefingItemPlayer,
    ) : this(
        controller,
        builder,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        sessionStore,
        itemPlayer = itemPlayer,
    ) {
        scope.launch { restore() }
    }

    private val _session = MutableStateFlow<BriefingSession?>(null)

    /** The live briefing, or null when none is playing. UI observes this. */
    val session: StateFlow<BriefingSession?> = _session.asStateFlow()

    /** The advance listener for the active briefing; cancelled on stop/finish. */
    private var listenerJob: Job? = null

    /** The advance trigger of the active briefing, kept so it can be saved and restored. */
    private var advanceOnChapterDone: Boolean = false

    /** Serializes store writes; each write saves the state current when it runs. */
    private val persistLock = Mutex()

    /**
     * Build the queue for [config] and start playing it as one episode.
     * Returns false (and starts nothing) when the build resolved zero items —
     * e.g. every configured source was off or failed.
     */
    suspend fun start(config: BriefingConfig): Boolean {
        stop()
        // Briefing items are single chapters: advance per item, not per fiction.
        return startWith(builder.build(config), advanceOnChapterDone = true)
    }

    /**
     * Play an already-assembled queue (a prebuilt morning briefing, or any
     * other caller's cross-fiction playlist — e.g. the #1675 "For you" feed)
     * as one continuous episode, beginning at [startIndex] (coerced into
     * range). Returns false and starts nothing when [items] is empty.
     *
     * [advanceOnChapterDone] picks the advance trigger:
     *  - `false` — advance on [PlaybackUiEvent.BookFinished], i.e. each item
     *    plays its whole fiction out (the controller's in-fiction auto-advance
     *    runs first).
     *  - `true` — per-item semantics: advance on
     *    [PlaybackUiEvent.ChapterDone] **only** when its chapterId is the
     *    current item's, and ignore `BookFinished`. On a fiction's last chapter
     *    both events fire, so listening to both would double-advance.
     *    `ChapterDone` is emitted before the engine's in-fiction
     *    `ChapterChanged`, so our `play(next)` overrides that advance. A
     *    multi-chapter RSS feed therefore contributes exactly its one item.
     */
    suspend fun startWith(
        items: List<BriefingItem>,
        startIndex: Int = 0,
        advanceOnChapterDone: Boolean = false,
    ): Boolean {
        stop()
        if (items.isEmpty()) return false
        _session.value = BriefingSession(items = items, index = startIndex.coerceIn(0, items.lastIndex))
        attach(advanceOnChapterDone)
        persist()
        playCurrent()
        return true
    }

    private fun attach(advanceOnChapterDone: Boolean) {
        this.advanceOnChapterDone = advanceOnChapterDone
        listenerJob = scope.launch {
            controller.events.collect { ev ->
                if (shouldAdvance(ev, _session.value, advanceOnChapterDone)) onCurrentItemFinished()
            }
        }
    }

    /**
     * Save the live session (or clear the store when there is none or it has
     * finished). Launched rather than awaited so [stop] can stay non-suspending;
     * the lock plus reading state inside it means the last write always matches
     * the latest state, whatever order the writes were queued in.
     */
    private fun persist() {
        scope.launch {
            persistLock.withLock {
                val live = _session.value?.takeUnless { it.finished }
                sessionStore.saveSession(
                    live?.let { SavedBriefingSession(it.items, it.index, advanceOnChapterDone, clock()) },
                )
            }
        }
    }

    /**
     * Re-attach a briefing that a process death interrupted: its queue and
     * cursor come back and the advance listener resumes, so the current item's
     * end moves on to the next one. It does NOT call play(): resuming the
     * current chapter is the player's job, and a restore must never start audio
     * on its own. A session older than [STALE_AFTER_MS] is dropped. Returns true
     * when a session was restored.
     */
    suspend fun restore(): Boolean {
        if (_session.value != null) return false
        val saved = sessionStore.loadSession() ?: return false
        val usable = saved.items.isNotEmpty() && saved.index in saved.items.indices &&
            clock() - saved.savedAtMillis <= STALE_AFTER_MS
        if (!usable) {
            sessionStore.saveSession(null)
            return false
        }
        // A briefing the user started while the store was loading wins.
        if (_session.value != null) return false
        _session.value = BriefingSession(items = saved.items, index = saved.index)
        attach(saved.advanceOnChapterDone)
        return true
    }

    /** Stop the briefing and detach the advance listener. Does not stop the player. */
    fun stop() {
        listenerJob?.cancel()
        listenerJob = null
        _session.value = null
        persist()
    }

    /** End-of-item hook: advance the cursor and either play the next item or finish. */
    private suspend fun onCurrentItemFinished() {
        val current = _session.value ?: return
        val next = advance(current)
        _session.value = next
        persist()
        if (next.finished) {
            listenerJob?.cancel()
            listenerJob = null
        } else {
            playCurrent()
        }
    }

    /** Start the current item through [itemPlayer] (#1810: never a bare play()). */
    private suspend fun playCurrent() {
        val item = _session.value?.current ?: return
        itemPlayer.playItem(item.fictionId, item.chapterId)
    }

    internal companion object {
        /** A briefing interrupted this long ago is no longer worth resuming. */
        const val STALE_AFTER_MS: Long = 12L * 60 * 60 * 1000

        /** Pure advance-trigger decision; see [startWith] for the two modes. */
        fun shouldAdvance(
            event: PlaybackUiEvent,
            session: BriefingSession?,
            advanceOnChapterDone: Boolean,
        ): Boolean {
            val current = session?.current ?: return false
            return if (advanceOnChapterDone) {
                event is PlaybackUiEvent.ChapterDone && event.chapterId == current.chapterId
            } else {
                event is PlaybackUiEvent.BookFinished
            }
        }

        /**
         * Pure cursor transition: given a session, produce the session after the
         * current item finishes. Advancing off the end marks it [finished]
         * (index parked at `items.size`) rather than wrapping. Extracted as pure
         * logic so the queue's core behavior is unit-tested without a player.
         */
        fun advance(session: BriefingSession): BriefingSession {
            val next = session.index + 1
            return if (next >= session.items.size) {
                session.copy(index = session.items.size, finished = true)
            } else {
                session.copy(index = next)
            }
        }
    }
}

/**
 * Immutable snapshot of an in-flight briefing: the resolved queue plus the
 * cursor. [finished] latches true once the last item has played out.
 */
data class BriefingSession(
    val items: List<BriefingItem>,
    val index: Int,
    val finished: Boolean = false,
) {
    /** The item currently playing, or null once finished / out of range. */
    val current: BriefingItem? get() = items.getOrNull(index)

    /** 1-based position for UI ("3 of 12"); 0 when finished/empty. */
    val position: Int get() = if (finished) items.size else (index + 1).coerceAtMost(items.size)
}
