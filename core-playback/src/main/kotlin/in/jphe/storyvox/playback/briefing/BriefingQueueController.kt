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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The morning-briefing "stitcher" (#1467) — the piece that was missing.
 *
 * `PlaybackController` auto-advances **within** a fiction and then emits
 * [PlaybackUiEvent.BookFinished] at the end; it has no concept of a queue that
 * spans *different* fictions/sources. This controller adds exactly that layer,
 * and nothing more: it holds an ordered [BriefingItem] queue, plays the current
 * item through the real load path ([PlaybackController.play] — never bare
 * navigation, cf. the #1455 class of "opened without loading" bugs), listens
 * for `BookFinished`, and advances to the next item. It never reaches into
 * `EnginePlayer`.
 *
 * Scoped as a **`@Singleton`** alongside `PlaybackController`, not to any
 * ViewModel/composable: a hands-free briefing must keep advancing when the user
 * navigates away from whatever screen started it. (Process-death recovery of
 * the cursor is a deliberate follow-up; surviving screen changes is slice 1.)
 */
@Singleton
class BriefingQueueController(
    private val controller: PlaybackController,
    private val builder: BriefingBuilder,
    private val scope: CoroutineScope,
) {
    /**
     * Hilt entry point. The real app gets a long-lived Default-dispatcher scope
     * that outlives every screen; tests use the primary constructor to inject a
     * controllable scope.
     */
    @Inject
    constructor(
        controller: PlaybackController,
        builder: BriefingBuilder,
    ) : this(controller, builder, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _session = MutableStateFlow<BriefingSession?>(null)

    /** The live briefing, or null when none is playing. UI observes this. */
    val session: StateFlow<BriefingSession?> = _session.asStateFlow()

    /** The `BookFinished` listener for the active briefing; cancelled on stop/finish. */
    private var listenerJob: Job? = null

    /**
     * Build the queue for [config] and start playing it as one episode.
     * Returns false (and starts nothing) when the build resolved zero items —
     * e.g. every configured source was off or failed.
     */
    suspend fun start(config: BriefingConfig): Boolean {
        stop()
        val items = builder.build(config)
        if (items.isEmpty()) return false
        _session.value = BriefingSession(items = items, index = 0)
        listenerJob = scope.launch {
            controller.events.collect { ev ->
                if (ev is PlaybackUiEvent.BookFinished) onCurrentItemFinished()
            }
        }
        playCurrent()
        return true
    }

    /** Stop the briefing and detach the advance listener. Does not stop the player. */
    fun stop() {
        listenerJob?.cancel()
        listenerJob = null
        _session.value = null
    }

    /** End-of-item hook: advance the cursor and either play the next item or finish. */
    private suspend fun onCurrentItemFinished() {
        val current = _session.value ?: return
        val next = advance(current)
        _session.value = next
        if (next.finished) {
            listenerJob?.cancel()
            listenerJob = null
        } else {
            playCurrent()
        }
    }

    private suspend fun playCurrent() {
        val item = _session.value?.current ?: return
        controller.play(item.fictionId, item.chapterId)
    }

    internal companion object {
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
