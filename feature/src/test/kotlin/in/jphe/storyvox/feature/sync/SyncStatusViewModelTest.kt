package `in`.jphe.storyvox.feature.sync

import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Issue #500 — state-table verification for the
 * [SyncStatusViewModel.deriveIndicator] pure mapper.
 *
 * The mapper is the load-bearing piece of the cloud-icon affordance:
 * if it drifts, the icon shows the wrong state and the user loses
 * confidence in the sync surface. Exhaustive table-tests rather than
 * combinatorial coroutine VM tests because the mapper is the
 * decision point — the StateFlow wiring is mechanical.
 */
class SyncStatusViewModelTest {

    private val user = SignedInUser(
        userId = "u1",
        email = "user@example.com",
        refreshToken = "tok",
    )

    @Test
    fun `null user means SignedOut regardless of status`() {
        // Even a coordinator chattering away can't override the
        // signed-out state. The icon should never show "syncing" when
        // there's no session — the underlying syncer would no-op on
        // a null user anyway.
        assertSame(
            SyncIndicator.SignedOut,
            SyncStatusViewModel.deriveIndicator(user = null, status = emptyMap()),
        )
        assertSame(
            SyncIndicator.SignedOut,
            SyncStatusViewModel.deriveIndicator(
                user = null,
                status = mapOf("library" to SyncStatus.Running),
            ),
        )
    }

    @Test
    fun `signed-in plus empty status map means SignedIn`() {
        // Fresh sign-in, no syncs fired yet — the icon should already
        // read "signed in" rather than waiting for the first push to
        // complete. This is the first-launch-after-sign-in moment.
        assertSame(
            SyncIndicator.SignedIn,
            SyncStatusViewModel.deriveIndicator(user = user, status = emptyMap()),
        )
    }

    @Test
    fun `signed-in plus all-Idle status means SignedIn`() {
        // Coordinator has initialized but no syncer is active right
        // now. Steady checkmark.
        val status = mapOf(
            "library" to SyncStatus.Idle,
            "follows" to SyncStatus.Idle,
        )
        assertSame(
            SyncIndicator.SignedIn,
            SyncStatusViewModel.deriveIndicator(user = user, status = status),
        )
    }

    @Test
    fun `signed-in plus one Running means Syncing`() {
        // A single domain pushing is enough to flip the icon — the
        // user wants to see "something is happening" the moment any
        // syncer fires, not wait until every domain is in motion.
        val status = mapOf(
            "library" to SyncStatus.Idle,
            "follows" to SyncStatus.Running,
            "settings" to SyncStatus.OkAt(at = 0L, records = 1),
        )
        assertSame(
            SyncIndicator.Syncing,
            SyncStatusViewModel.deriveIndicator(user = user, status = status),
        )
    }

    @Test
    fun `signed-in plus Transient and Permanent still reads as SignedIn`() {
        // Error states don't show as "syncing" — the icon's job is
        // to communicate "are we actively in flight." A failed
        // domain shows in the bottom sheet's per-domain detail; the
        // top-level icon stays calm.
        val status = mapOf(
            "library" to SyncStatus.Transient("network"),
            "secrets" to SyncStatus.Permanent("passphrase missing"),
        )
        assertSame(
            SyncIndicator.SignedIn,
            SyncStatusViewModel.deriveIndicator(user = user, status = status),
        )
    }

    @Test
    fun `signed-in plus Running plus Permanent still reads as Syncing`() {
        // If anything is currently moving, prefer "syncing" over
        // "signed in" — the user cares more about live state than
        // historical errors when reading the icon. Errors live in
        // the sheet body.
        val status = mapOf(
            "library" to SyncStatus.Running,
            "secrets" to SyncStatus.Permanent("passphrase missing"),
        )
        assertSame(
            SyncIndicator.Syncing,
            SyncStatusViewModel.deriveIndicator(user = user, status = status),
        )
    }

    @Test
    fun `indicator instances are stable singletons`() {
        // The sealed-class objects should compare by identity so a
        // Compose recomposition with the same indicator value doesn't
        // re-instantiate the cloud icon. Cheap regression guard
        // against accidentally turning these into data classes.
        assertSame(SyncIndicator.SignedIn, SyncIndicator.SignedIn)
        assertSame(SyncIndicator.Syncing, SyncIndicator.Syncing)
        assertSame(SyncIndicator.SignedOut, SyncIndicator.SignedOut)
    }

    @Test
    fun `every status type contributes a derivable indicator`() {
        // Defensive coverage: if a future SyncStatus subtype lands
        // (e.g. Paused, Queued) the mapper's `when` becomes
        // non-exhaustive and the compiler complains. This test
        // documents the expected set today as a snapshot.
        val every: List<SyncStatus> = listOf(
            SyncStatus.Idle,
            SyncStatus.Running,
            SyncStatus.OkAt(at = 0L, records = 0),
            SyncStatus.Transient("x"),
            SyncStatus.Permanent("y"),
        )
        // Wrap each in a single-element map and assert no crash.
        every.forEach { s ->
            val indicator = SyncStatusViewModel.deriveIndicator(
                user = user,
                status = mapOf("d" to s),
            )
            // Running → Syncing, everything else → SignedIn (with
            // the user set). Cross-check matches the four other
            // tests above and locks the table.
            val expected = if (s is SyncStatus.Running) SyncIndicator.Syncing else SyncIndicator.SignedIn
            assertEquals("status $s", expected, indicator)
        }
    }
}
