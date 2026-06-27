package `in`.jphe.storyvox.sync.client

import kotlinx.serialization.Serializable

/**
 * Abstract surface for InstantDB data operations: fetch / push a row by
 * (entityName, id), where the row payload is a single JSON object.
 *
 * v1 maps each "domain" to a single deterministic id per user:
 *   - `sets/library/<user-id>`     — the user's library set membership + tombstones
 *   - `sets/follows/<user-id>`     — same shape, followed fictions
 *   - `blobs/pronunciation/<user-id>` — pronunciation dict JSON blob
 *   - `blobs/secrets/<user-id>`    — encrypted secrets envelope
 *   - `blobs/settings/<user-id>`   — settings JSON blob (LWW)
 *   - `positions/<user-id>:<fictionId>` — one row per fiction
 *
 * Why one row per blob (instead of per-field rows): a chatty
 * per-preference schema would push 80+ rows for the settings layer
 * alone. A single blob is one transact per sync round, leaves the
 * domain in charge of its own structure, and makes LWW trivially
 * correct (one `updatedAt` per blob).
 *
 * Implementation seam:
 *  - The default production wiring (will be) a thin OkHttp WebSocket
 *    client that posts the wire-format transact envelope from
 *    [InstantTransact]. Today the production binding is a stub that
 *    reports "sync backend not configured" if the app id is the
 *    sentinel PLACEHOLDER — so the app still builds and the UI shows
 *    "Sync disabled" instead of crashing on the first push.
 *  - Tests substitute a [FakeInstantBackend] that holds an in-memory
 *    map. This lets every Syncer be unit-tested without spinning up
 *    a WebSocket.
 */
interface InstantBackend {
    /** Fetch a row by entity + id. Returns null if the row does not exist. */
    suspend fun fetch(user: SignedInUser, entity: String, id: String): Result<RowSnapshot?>

    /** Upsert a row's payload. The server should atomically replace the
     *  payload + updatedAt. */
    suspend fun upsert(
        user: SignedInUser,
        entity: String,
        id: String,
        payload: String,
        updatedAt: Long,
    ): Result<Unit>

    /**
     * Delete a row by entity + id. Used by the sign-out data-deletion path
     * (#1139) so signing out actually removes the user's cloud record, as
     * the privacy policy promises.
     *
     * Idempotent: deleting a row that doesn't exist (a domain the user never
     * pushed) is a success, not an error — InstantDB's transact treats a
     * delete of a missing entity as a no-op. So a blanket purge across every
     * domain is safe even when only some rows were ever written.
     */
    suspend fun delete(user: SignedInUser, entity: String, id: String): Result<Unit>

    /** Whether the backend is wired up to talk to a real InstantDB.
     *  False when [BuildConfig.INSTANTDB_APP_ID] is the sentinel. */
    val isConfigured: Boolean
}

@Serializable
data class RowSnapshot(
    val payload: String,
    val updatedAt: Long,
)

/**
 * "Sync disabled" implementation — used when the app id is the placeholder
 * sentinel ("PLACEHOLDER"). Every operation returns a transient failure
 * with a clear message; the UI surfaces "Sync isn't configured for this
 * build" so JP can paste in the real app id once the InstantDB app is
 * provisioned.
 */
class DisabledBackend : InstantBackend {
    override val isConfigured: Boolean = false
    override suspend fun fetch(user: SignedInUser, entity: String, id: String): Result<RowSnapshot?> =
        Result.failure(IllegalStateException(NOT_CONFIGURED))
    override suspend fun upsert(
        user: SignedInUser,
        entity: String,
        id: String,
        payload: String,
        updatedAt: Long,
    ): Result<Unit> = Result.failure(IllegalStateException(NOT_CONFIGURED))

    /** Vacuous success: with sync disabled nothing was ever written to
     *  InstantDB, so there is nothing to delete. The sign-out deletion path
     *  (#1139) must not report a spurious failure just because this build
     *  has no sync backend. */
    override suspend fun delete(user: SignedInUser, entity: String, id: String): Result<Unit> =
        Result.success(Unit)

    private companion object {
        const val NOT_CONFIGURED = "Sync isn't configured for this build (placeholder INSTANTDB_APP_ID)"
    }
}

/** In-memory fake — useful for tests AND as a self-host backstop when
 *  the user hasn't configured cloud sync but the rest of the app expects
 *  the seam to exist. Persists nothing across process restarts. */
class FakeInstantBackend : InstantBackend {
    override val isConfigured: Boolean = true
    private val store = mutableMapOf<Pair<String, String>, RowSnapshot>()

    override suspend fun fetch(user: SignedInUser, entity: String, id: String): Result<RowSnapshot?> =
        Result.success(store[entity to id])

    override suspend fun upsert(
        user: SignedInUser,
        entity: String,
        id: String,
        payload: String,
        updatedAt: Long,
    ): Result<Unit> {
        store[entity to id] = RowSnapshot(payload, updatedAt)
        return Result.success(Unit)
    }

    override suspend fun delete(user: SignedInUser, entity: String, id: String): Result<Unit> {
        // Idempotent like the real backend: removing an absent key is fine.
        store.remove(entity to id)
        return Result.success(Unit)
    }
}
