package `in`.jphe.storyvox.sync.domain

import android.content.SharedPreferences
import `in`.jphe.storyvox.sync.SyncIds
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import `in`.jphe.storyvox.sync.coordinator.toPurgeOutcome

/**
 * Issue #1469 — imports items pushed from the desktop / homelab into the
 * Library. See [InboxPayload] for the wire shape and ownership model, and
 * `docs/push-to-candela.md` for the user-facing flow.
 *
 * - [pull] reads the inbox row, hands each unseen item to the [InboxSink],
 *   and records the outcome in the device-local [InboxSeenStore].
 * - [push] is a deliberate no-op: the phone never writes the inbox row (the
 *   pushers own it — see [InboxPayload] "Ownership model").
 * - [purge] deletes the row on sign-out like every other domain (#1139).
 *
 * Per-item failures never fail the round: a Retry item is counted and
 * re-offered on the next poll, and the round reports Ok. Only a failed
 * remote fetch is Transient (so the coordinator's backoff covers a network
 * blip without re-importing anything — seen-state is only written after the
 * items were handled).
 */
class InboxSyncer(
    private val backend: InstantBackend,
    private val sink: InboxSink,
    private val seenStore: InboxSeenStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Syncer {

    override val name: String get() = DOMAIN

    override suspend fun push(user: SignedInUser): SyncOutcome = SyncOutcome.Ok(0)

    override suspend fun pull(user: SignedInUser): SyncOutcome {
        val snap = backend.fetch(user, ENTITY, rowId(user)).getOrElse {
            return SyncOutcome.Transient("inbox fetch: ${it.message}")
        }
        val prior = seenStore.read()
        // Missing row = nobody has pushed yet. Still mark the device
        // initialized: a push that lands AFTER this first successful look is
        // by definition new to this device, whatever its createdAt.
        val payload = snap?.let { InboxLogic.decode(it.payload) } ?: InboxPayload()
        val now = clock()
        val selection = InboxLogic.select(payload, prior, now)
        val results = LinkedHashMap<String, InboxDelivery>()
        for (item in selection.deliver) {
            val reason = InboxLogic.invalidReason(item)
            results[item.id] = if (reason != null) {
                android.util.Log.w(TAG, "rejecting ${item.id.take(8)}: $reason")
                InboxDelivery.Rejected
            } else {
                try {
                    sink.deliver(item)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "deliver ${item.id.take(8)} threw: ${e.message}")
                    InboxDelivery.Retry
                }
            }
        }
        seenStore.write(InboxLogic.fold(prior, payload, selection.silentlySeen, results))
        val delivered = results.values.count { it == InboxDelivery.Delivered }
        if (results.isNotEmpty() || selection.silentlySeen.isNotEmpty()) {
            android.util.Log.w(
                TAG,
                "inbox: delivered=$delivered offered=${results.size} " +
                    "skippedAsOld=${selection.silentlySeen.size}",
            )
        }
        return SyncOutcome.Ok(recordsAffected = delivered)
    }

    override suspend fun purge(user: SignedInUser): SyncOutcome =
        backend.delete(user, ENTITY, rowId(user)).toPurgeOutcome()

    private fun rowId(user: SignedInUser) = SyncIds.rowUuid(DOMAIN, user.userId)

    companion object {
        const val DOMAIN: String = "inbox"
        const val ENTITY: String = "blobs"
        private const val TAG = "InboxSyncer"
    }
}

/** [InboxSeenStore] over the app's shared [SharedPreferences] — one JSON
 *  string under [KEY]. */
class PrefsInboxSeenStore(private val prefs: SharedPreferences) : InboxSeenStore {
    override suspend fun read(): InboxSeenState = InboxLogic.decodeSeen(prefs.getString(KEY, null))
    override suspend fun write(state: InboxSeenState) {
        prefs.edit().putString(KEY, InboxLogic.encodeSeen(state)).apply()
    }

    private companion object {
        const val KEY = "sync.inbox.seen.v1"
    }
}
