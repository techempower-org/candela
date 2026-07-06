package `in`.jphe.storyvox.deadline

import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminder
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminderScheduler
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminderStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1631 — the toggle-flip half of the deadline-reminder regression
 * bar: OFF cancels every armed alarm, ON re-arms from the store, and the
 * store is never mutated (a saved benefits deadline is never deleted by
 * flipping the master toggle).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeadlineReminderReconcilerTest {

    private val r1 = reminder("a")
    private val r2 = reminder("b")

    @Test
    fun `enabling re-arms every stored reminder`() = runTest {
        val store = FakeStore(mutableListOf(r1, r2))
        val scheduler = FakeScheduler()
        val reconciler = DeadlineReminderReconciler(FakeSettings(), scheduler, store)

        reconciler.reconcile(enabled = true)

        assertEquals(listOf("a", "b"), scheduler.scheduled.map { it.id })
        assertTrue("nothing cancelled on ON", scheduler.cancelled.isEmpty())
        assertEquals("store untouched", 2, store.all().size)
    }

    @Test
    fun `disabling cancels every stored reminder but never deletes the store`() = runTest {
        val store = FakeStore(mutableListOf(r1, r2))
        val scheduler = FakeScheduler()
        val reconciler = DeadlineReminderReconciler(FakeSettings(), scheduler, store)

        reconciler.reconcile(enabled = false)

        assertEquals(listOf("a", "b"), scheduler.cancelled.map { it.id })
        assertTrue("nothing armed on OFF", scheduler.scheduled.isEmpty())
        // The saved deadlines survive — turning off is reversible.
        assertEquals("store untouched", 2, store.all().size)
    }

    // ── Fakes ──────────────────────────────────────────────────────────

    private fun reminder(id: String) = DeadlineReminder(
        id = id,
        programId = null,
        label = id,
        deadlineEpochDay = 20_000L,
        notificationTitle = id,
        notificationBody = id,
        createdEpochDay = 19_000L,
    )

    /** reconcile() never reads [settings], so an empty flow suffices (avoids
     *  constructing the 90+-field UiSettings). */
    private class FakeSettings : SettingsRepositoryUi {
        override val settings: Flow<UiSettings> = emptyFlow()
    }

    private class FakeStore(private val items: MutableList<DeadlineReminder>) : DeadlineReminderStore {
        override fun reminders(): Flow<List<DeadlineReminder>> = flowOf(items)
        override suspend fun all(): List<DeadlineReminder> = items.toList()
        override suspend fun upsert(reminder: DeadlineReminder) { items += reminder }
        override suspend fun delete(id: String) { items.removeAll { it.id == id } }
    }

    private class FakeScheduler : DeadlineReminderScheduler {
        val scheduled = mutableListOf<DeadlineReminder>()
        val cancelled = mutableListOf<DeadlineReminder>()
        override fun schedule(reminder: DeadlineReminder) { scheduled += reminder }
        override fun cancel(reminder: DeadlineReminder) { cancelled += reminder }
        override fun rescheduleAll(reminders: List<DeadlineReminder>) { scheduled += reminders }
        override fun canScheduleExact(): Boolean = true
    }
}
