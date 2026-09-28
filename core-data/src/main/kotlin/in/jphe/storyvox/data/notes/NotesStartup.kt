package `in`.jphe.storyvox.data.notes

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Voice Notes (#1657) — the once-per-process startup hook for
 * [NotesRepository.sweepOrphanAudio]. `StoryvoxApp.onCreate` calls [start]
 * with its deferred-init scope (Issue #409 cold-launch posture); this class
 * owns the three guarantees that call needs, so they are JVM-testable without
 * an `Application`:
 *
 *  - **Once per process** — a second [start] is a no-op, even from another scope.
 *  - **Off the main thread** — the sweep (a DAO read + `listFiles` + deletes)
 *    runs on [dispatcher] (`Dispatchers.IO` in production).
 *  - **Never crashes startup** — the app's init scope has a `SupervisorJob` but
 *    NO exception handler, so an uncaught throw there kills the process. Every
 *    failure is caught and reported to [onError]; the sweep simply retries on
 *    the next launch. Cancellation still propagates.
 *
 * The sweep runs with [ORPHAN_GRACE_MS]: a recording is written to disk before
 * its row exists, so a file touched in the last minute is never reclaimed.
 */
@Singleton
class NotesStartup internal constructor(
    private val sweep: suspend () -> Int,
    private val dispatcher: CoroutineDispatcher,
    private val onError: (Throwable) -> Unit,
) {

    @Inject
    constructor(repo: NotesRepository) : this(
        sweep = { repo.sweepOrphanAudio(minAgeMs = ORPHAN_GRACE_MS) },
        dispatcher = Dispatchers.IO,
        onError = { t -> Log.w(TAG, "orphan-audio sweep failed; will retry next launch", t) },
    )

    private val started = AtomicBoolean(false)

    /**
     * Launch the sweep in [scope] on [dispatcher]. Returns the job, or null when
     * this process already started it.
     */
    fun start(scope: CoroutineScope): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch(dispatcher) {
            try {
                val reclaimed = sweep()
                if (reclaimed > 0) Log.i(TAG, "reclaimed $reclaimed orphan recording(s)")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                runCatching { onError(t) }
            }
        }
    }

    companion object {
        private const val TAG = "NotesStartup"

        /** Files modified within this window are never swept (a take may be mid-write). */
        const val ORPHAN_GRACE_MS: Long = 60_000L
    }
}
