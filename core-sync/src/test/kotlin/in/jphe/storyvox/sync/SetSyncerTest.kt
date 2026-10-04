package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.TombstonesAccess
import `in`.jphe.storyvox.sync.domain.BackendSetRemote
import `in`.jphe.storyvox.sync.domain.SetSyncer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetSyncerTest {

    private val USER = SignedInUser(userId = "u-1", email = null, refreshToken = "rt-1")

    /** Pure-JVM tombstone store substitute. The production
     *  [TombstoneStore] is a DataStore wrapper; tests substitute this
     *  in-memory implementation through the [TombstonesAccess]
     *  interface so they don't need a Robolectric runtime.
     *
     *  Issue #360 finding 3: tracks per-id stamps so the timestamped
     *  union-with-tombstones path can be exercised. Stamps default to
     *  `clock()` at add-time; tests override the clock to fast-forward
     *  past the TTL. */
    private class InMemoryTombstones(
        private val clock: () -> Long = System::currentTimeMillis,
    ) : TombstonesAccess {
        private val byDomain = mutableMapOf<String, MutableMap<String, Long>>()
        override fun observe(domain: String): Flow<Set<String>> =
            flowOf(byDomain[domain]?.keys?.toSet() ?: emptySet())
        override suspend fun snapshot(domain: String): Set<String> =
            byDomain[domain]?.keys?.toSet() ?: emptySet()
        override suspend fun snapshotWithStamps(domain: String): Map<String, Long> =
            byDomain[domain]?.toMap() ?: emptyMap()
        override suspend fun add(domain: String, id: String) {
            byDomain.getOrPut(domain) { mutableMapOf() }[id] = clock()
        }
        override suspend fun addAll(domain: String, ids: Collection<String>) {
            val now = clock()
            val bucket = byDomain.getOrPut(domain) { mutableMapOf() }
            for (id in ids) bucket[id] = now
        }
        override suspend fun forget(domain: String, id: String) {
            byDomain[domain]?.remove(id)
        }
        override suspend fun clear(domain: String) {
            byDomain.remove(domain)
        }
    }

    @Test fun `first-time push of local set lands the members on remote`() = runTest {
        val backend = FakeInstantBackend()
        val localState = mutableSetOf("a", "b", "c")
        val syncer = SetSyncer(
            name = "library",
            tombstones = InMemoryTombstones(),
            localSnapshot = { localState.toSet() },
            localAdd = { localState.add(it) },
            localRemove = { localState.remove(it) },
            remote = BackendSetRemote("library", backend),
        )

        val outcome = syncer.push(USER)
        assertTrue(outcome is SyncOutcome.Ok)

        // A second "device" pulls — local empty → ends up with all
        // three. This is the migration story end-to-end.
        val device2 = mutableSetOf<String>()
        val syncer2 = SetSyncer(
            name = "library",
            tombstones = InMemoryTombstones(),
            localSnapshot = { device2.toSet() },
            localAdd = { device2.add(it) },
            localRemove = { device2.remove(it) },
            remote = BackendSetRemote("library", backend),
        )
        val pull2 = syncer2.pull(USER)
        assertTrue(pull2 is SyncOutcome.Ok)
        assertEquals(setOf("a", "b", "c"), device2)
    }

    @Test fun `purge deletes the remote set row but keeps local (issue 1139)`() = runTest {
        val backend = FakeInstantBackend()
        val localState = mutableSetOf("a", "b")
        val syncer = SetSyncer(
            name = "library",
            tombstones = InMemoryTombstones(),
            localSnapshot = { localState.toSet() },
            localAdd = { localState.add(it) },
            localRemove = { localState.remove(it) },
            remote = BackendSetRemote("library", backend),
        )
        syncer.push(USER)
        val rowId = SyncIds.rowUuid("library", USER.userId)
        assertNotNull("remote row exists after push", backend.fetch(USER, "sets", rowId).getOrThrow())

        val outcome = syncer.purge(USER)
        assertTrue(outcome is SyncOutcome.Ok)
        assertNull("remote set row deleted by purge", backend.fetch(USER, "sets", rowId).getOrThrow())
        // Sign-out deletes the CLOUD copy only — local membership is retained.
        assertEquals(setOf("a", "b"), localState)
    }

    @Test fun `tombstone on one device removes the member on the other after sync`() = runTest {
        val backend = FakeInstantBackend()

        val tombA = InMemoryTombstones()
        tombA.add("library", "b")
        val deviceA = mutableSetOf("a", "c")
        SetSyncer(
            "library", tombA,
            { deviceA.toSet() }, { deviceA.add(it) }, { deviceA.remove(it) },
            BackendSetRemote("library", backend),
        ).push(USER)

        val deviceB = mutableSetOf("a", "b", "c")
        SetSyncer(
            "library", InMemoryTombstones(),
            { deviceB.toSet() }, { deviceB.add(it) }, { deviceB.remove(it) },
            BackendSetRemote("library", backend),
        ).pull(USER)

        // After the pull, device B should no longer have b — the
        // tombstone propagated through the cloud.
        assertEquals(setOf("a", "c"), deviceB)
    }

    @Test fun `re-add after tombstone expiry propagates on next sync`() = runTest {
        // Issue #360 finding 3 (argus): tombstones used to be
        // immortal — once an id was removed, a re-add was filtered
        // forever. With the TTL fix (default 24h), a tombstone older
        // than the freshness window no longer blocks the re-add.
        //
        // We don't actually wait 24h in a unit test; instead we use
        // the `unionWithTombstoneStamps` policy directly to assert
        // the behaviour, then exercise the whole sync flow with an
        // expired stamp.
        val backend = FakeInstantBackend()
        // Tombstones recorded "long ago" — stamp = 0L, which is
        // always past the 24h TTL from any sensible `now`.
        val ancientTombs = InMemoryTombstones(clock = { 0L })

        // Device A: had "fic-1" removed forever ago (tombstone at 0L).
        val deviceA = mutableSetOf<String>()
        ancientTombs.add("library", "fic-1")
        // Now the user re-adds "fic-1" on device A.
        deviceA.add("fic-1")
        val syncerA = SetSyncer(
            "library", ancientTombs,
            { deviceA.toSet() }, { deviceA.add(it) }, { deviceA.remove(it) },
            BackendSetRemote("library", backend),
        )
        syncerA.push(USER)

        // The re-add must be visible to a fresh device B that pulls.
        val deviceB = mutableSetOf<String>()
        val syncerB = SetSyncer(
            "library", InMemoryTombstones(),
            { deviceB.toSet() }, { deviceB.add(it) }, { deviceB.remove(it) },
            BackendSetRemote("library", backend),
        )
        syncerB.pull(USER)
        assertTrue("re-added fic-1 must survive an expired tombstone", "fic-1" in deviceB)
    }

    @Test fun `fresh tombstone still wins over a re-add within the TTL window`() = runTest {
        // The TTL fix doesn't make tombstones useless — a tombstone
        // recorded *now* still blocks an add of the same id. This
        // guards the "removal propagates" contract from issue 3's
        // partner test (which lives above).
        val backend = FakeInstantBackend()
        val freshClock: () -> Long = { System.currentTimeMillis() }
        val tombsA = InMemoryTombstones(clock = freshClock)
        tombsA.add("library", "fic-2")
        val deviceA = mutableSetOf<String>("fic-2")
        SetSyncer(
            "library", tombsA,
            { deviceA.toSet() }, { deviceA.add(it) }, { deviceA.remove(it) },
            BackendSetRemote("library", backend),
        ).push(USER)
        // Per the existing "remove wins" path, fic-2 is gone after sync.
        assertTrue("fresh tombstone removes the member", "fic-2" !in deviceA)
    }

    @Test fun `union takes both sides additions`() = runTest {
        val backend = FakeInstantBackend()
        val deviceA = mutableSetOf("x")
        val deviceB = mutableSetOf("y")
        val syncerA = SetSyncer(
            "library", InMemoryTombstones(),
            { deviceA.toSet() }, { deviceA.add(it) }, { deviceA.remove(it) },
            BackendSetRemote("library", backend),
        )
        val syncerB = SetSyncer(
            "library", InMemoryTombstones(),
            { deviceB.toSet() }, { deviceB.add(it) }, { deviceB.remove(it) },
            BackendSetRemote("library", backend),
        )
        syncerA.push(USER)
        syncerB.push(USER)
        syncerA.pull(USER)
        assertEquals(setOf("x", "y"), deviceA)
    }
}
