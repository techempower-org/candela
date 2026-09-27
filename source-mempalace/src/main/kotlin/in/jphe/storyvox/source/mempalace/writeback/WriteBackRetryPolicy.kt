package `in`.jphe.storyvox.source.mempalace.writeback

import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonResult

/**
 * Issue #1468 — what the write-back worker does with one `POST /memory`
 * outcome. Pure, so the retry matrix is unit-tested without WorkManager
 * (see `WriteBackRetryPolicyTest`).
 *
 * The line it draws: **transient** failures (off the LAN, daemon asleep or
 * mid-rebuild, 5xx, 408/429) retry with WorkManager's exponential backoff;
 * **permanent** failures (bad key, host outside the LAN allowlist, 4xx such
 * as a room the daemon rejects) give up at once, because retrying them only
 * burns battery until the attempt cap. Every path is bounded by
 * [MAX_ATTEMPTS] so a palace that is gone for good stops being retried.
 */
object WriteBackRetryPolicy {

    /** Attempts before giving up. With a 30 s initial exponential backoff
     *  (WorkManager caps each delay at 5 h) this spans roughly a day and a
     *  half of the palace host being asleep or the phone being off-LAN. */
    const val MAX_ATTEMPTS: Int = 12

    /** Initial backoff, seconds — doubled by WorkManager per attempt. */
    const val INITIAL_BACKOFF_SECONDS: Long = 30

    enum class Decision { Done, Retry, GiveUp }

    /**
     * @param runAttemptCount WorkManager's `runAttemptCount` for this run —
     *   0 on the first run, incremented on every retry.
     */
    fun decide(result: PalaceDaemonResult<*>, runAttemptCount: Int): Decision {
        val transient = when (result) {
            is PalaceDaemonResult.Success -> return Decision.Done
            is PalaceDaemonResult.NotReachable -> true
            is PalaceDaemonResult.Degraded -> true
            is PalaceDaemonResult.HttpError -> isTransientHttp(result.code)
            is PalaceDaemonResult.Unauthorized -> false
            is PalaceDaemonResult.HostRejected -> false
            is PalaceDaemonResult.NotFound -> false
            is PalaceDaemonResult.ParseError -> false
        }
        if (!transient) return Decision.GiveUp
        return if (runAttemptCount + 1 >= MAX_ATTEMPTS) Decision.GiveUp else Decision.Retry
    }

    internal fun isTransientHttp(code: Int): Boolean =
        code >= 500 || code == 408 || code == 429
}
