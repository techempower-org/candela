package `in`.jphe.storyvox.playback.briefing

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
) : BriefingItemPlayer {
    override suspend fun playItem(fictionId: String, chapterId: String) {
        play(fictionId, chapterId) // RED-FIRST STUB (#1810): the bare play() the bug is about
    }
}
