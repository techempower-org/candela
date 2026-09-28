package `in`.jphe.storyvox.playback.briefing

import `in`.jphe.storyvox.playback.PendingUtteranceGate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * #1810 — how [BriefingQueueController] starts one queue item.
 *
 * A bare `PlaybackController.play()` isn't enough on its own. It does nothing
 * until the playback service has bound an engine, and the engine only reads
 * chapters whose body is already downloaded; a briefing item fresh from
 * Browse has neither. The Play buttons avoid this by going through the UI
 * layer's `startListening` (start the service, download, wait, then play).
 * The app binds an implementation that does the same for queue items.
 */
fun interface BriefingItemPlayer {
    suspend fun playItem(fictionId: String, chapterId: String)
}

/**
 * #1810 — the [BriefingItemPlayer] the app binds, as pure sequencing so it can
 * be unit-tested without a service or a device. The app supplies the Android
 * pieces. For one item it:
 *  1. starts the playback service if no engine is bound (a failed start, e.g.
 *     from the background, is reported and not thrown);
 *  2. queues the chapter download (on any network: the user asked to listen);
 *  3. waits for the chapter body, then for an engine to bind;
 *  4. plays it. If step 3 times out it reports why and plays nothing, rather
 *     than calling a play() that would silently do nothing.
 */
class PreparingBriefingItemPlayer(
    private val isEngineBound: () -> Boolean,
    private val startService: () -> Unit,
    private val queueDownload: suspend (fictionId: String, chapterId: String) -> Unit,
    private val awaitBody: suspend (chapterId: String) -> Boolean,
    private val awaitEngine: suspend () -> Boolean,
    private val play: suspend (fictionId: String, chapterId: String) -> Unit,
    private val onProblem: (String) -> Unit = {},
    /**
     * Where [play] runs. The app passes `Dispatchers.Main.immediate`: the queue
     * auto-advances from a listener on `Dispatchers.Default`, and Media3's
     * Player is Main-confined ("Player is accessed on the wrong thread" crash on
     * the tablet, #1810; same class as #1606).
     */
    private val playContext: CoroutineContext = EmptyCoroutineContext,
) : BriefingItemPlayer {
    override suspend fun playItem(fictionId: String, chapterId: String) {
        if (PendingUtteranceGate.needsServiceStart(isEngineBound())) {
            runCatching { startService() }
                .onFailure { onProblem("couldn't start the playback service for $chapterId: ${it.message}") }
        }
        queueDownload(fictionId, chapterId)
        if (!awaitBody(chapterId)) {
            onProblem("chapter $chapterId wasn't downloaded in time; not playing it")
            return
        }
        if (!awaitEngine()) {
            onProblem("no playback engine bound for $chapterId; not playing it")
            return
        }
        play(fictionId, chapterId) // RED-FIRST STUB: ignores playContext
    }
}
