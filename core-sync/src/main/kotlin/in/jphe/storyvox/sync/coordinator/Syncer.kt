package `in`.jphe.storyvox.sync.coordinator

import `in`.jphe.storyvox.sync.client.SignedInUser

/**
 * Contract for a per-domain sync handler.
 *
 * One implementation per "thing that should sync" — library, follows,
 * reading positions, bookmarks, pronunciation dict, secrets, etc. The
 * [SyncCoordinator] schedules calls; each syncer owns its local IO and
 * is responsible for pushing/pulling its own InstantDB entity.
 *
 * Why a thin interface and not a generic "sync everything" macro: each
 * domain has subtly different conflict rules (LWW vs union vs max), so
 * the syncer is where domain knowledge lives. The coordinator's job is
 * orchestration — when to call which, retries, surfacing failures.
 */
interface Syncer {

    /** Stable identifier — used in logs and as a dedup key in the
     *  coordinator. */
    val name: String

    /**
     * Push the local state to InstantDB. Called whenever the local
     * source mutates, plus on first sign-in to upload existing state
     * (the "migration" path called out in JP's brief).
     *
     * Implementations must be idempotent — the coordinator may retry on
     * transient failures, and a duplicate transact for the same entity
     * id with the same updatedAt should be a no-op.
     */
    suspend fun push(user: SignedInUser): SyncOutcome

    /**
     * Pull remote state into the local store. Called on cold start after
     * the refresh token verifies, and on any sign-in-on-a-fresh-install.
     *
     * Implementations apply the merge themselves — they own knowing
     * whether to LWW, union, or max-scalar.
     */
    suspend fun pull(user: SignedInUser): SyncOutcome

    /**
     * Delete this domain's remote (InstantDB) row(s) for [user]. Called by
     * [SyncCoordinator.purgeRemoteData] on sign-out so signing out actually
     * removes the user's cloud record — the deletion the privacy policy
     * promises (#1139).
     *
     * Deliberately ABSTRACT (no default): a privacy/compliance guarantee
     * must not silently skip a domain. Adding a new syncer forces a
     * deliberate `purge` decision at compile time, the same safety the
     * multibound `Set<Syncer>` gives the push/pull paths.
     *
     * Scope: this deletes only the CLOUD copy. Local on-device data is
     * intentionally left intact (signing out disables sync; uninstalling
     * clears local) — see [SyncCoordinator]'s class kdoc. Idempotent: a
     * domain the user never pushed deletes a missing row, which the backend
     * treats as success.
     */
    suspend fun purge(user: SignedInUser): SyncOutcome
}

/**
 * Map a remote-delete [Result] to the [SyncOutcome] the purge path expects.
 * Any failure is [SyncOutcome.Transient] — purge is best-effort on sign-out
 * and the privacy policy's email-request path backstops the offline case.
 */
internal fun Result<Unit>.toPurgeOutcome(): SyncOutcome = fold(
    onSuccess = { SyncOutcome.Ok(recordsAffected = 1) },
    onFailure = { SyncOutcome.Transient(it.message ?: "delete failed") },
)

/** Result of a single push or pull. */
sealed interface SyncOutcome {
    /** Everything went through. The optional [recordsAffected] is for
     *  logging / status UI only — there's no contract on the number. */
    data class Ok(val recordsAffected: Int = 0) : SyncOutcome

    /** Network or server failure. The coordinator will retry on its
     *  own schedule; the message is for status UI / logs. */
    data class Transient(val message: String) : SyncOutcome

    /** Permanent failure — corrupt local state, bad credentials, etc.
     *  The coordinator should NOT retry; the syncer's owner needs to
     *  intervene. */
    data class Permanent(val message: String) : SyncOutcome
}
