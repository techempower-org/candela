package `in`.jphe.storyvox.feature.techempower.deadline

import `in`.jphe.storyvox.data.ocr.OcrImage
import `in`.jphe.storyvox.data.ocr.OcrRecognition
import `in`.jphe.storyvox.data.ocr.OcrResult
import `in`.jphe.storyvox.data.ocr.OcrTextRecognizer
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #1515 — orchestration coverage for the deadline keeper VM, using
 * hand-rolled fakes (the codebase's plain-JVM test posture). Exercises the
 * scan → confirm → schedule → delete path with no Android / network in
 * sight — the whole flow is airplane-mode-safe by construction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeadlineKeeperViewModelTest {

    private val today = LocalDate.of(2026, 7, 4)

    private lateinit var recognizer: FakeRecognizer
    private lateinit var store: FakeStore
    private lateinit var scheduler: FakeScheduler
    private lateinit var vm: DeadlineKeeperViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        recognizer = FakeRecognizer(
            OcrResult.Success(OcrRecognition(text = "Please respond by August 31, 2026.")),
        )
        store = FakeStore()
        scheduler = FakeScheduler()
        vm = DeadlineKeeperViewModel(recognizer, store, scheduler, DeadlineClock { today })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `scanning a letter surfaces the deadline candidate`() = runTest {
        vm.onImageCaptured(ByteArray(0))
        val candidates = vm.state.value.candidates
        assertTrue(candidates.isNotEmpty())
        assertEquals(LocalDate.of(2026, 8, 31), candidates.first().date)
    }

    @Test
    fun `confirming a candidate persists and schedules a reminder`() = runTest {
        vm.onImageCaptured(ByteArray(0))
        val candidate = vm.state.value.candidates.first()
        vm.selectCandidate(candidate, label = "Benefits deadline", defaultBody = "due soon")
        assertEquals(LocalDate.of(2026, 8, 31), vm.state.value.draft?.deadline)

        vm.confirmDraft()

        assertNull("draft cleared after confirm", vm.state.value.draft)
        assertTrue("success signal set", vm.state.value.justScheduled)
        assertEquals(1, vm.state.value.reminders.size)
        val reminder = vm.state.value.reminders.first()
        assertEquals(LocalDate.of(2026, 8, 31), reminder.deadline)
        // Scheduler got exactly the persisted reminder.
        assertEquals(1, scheduler.scheduled.size)
        assertEquals(reminder.id, scheduler.scheduled.first().id)
    }

    @Test
    fun `blank label cannot confirm`() = runTest {
        vm.onImageCaptured(ByteArray(0))
        vm.selectCandidate(vm.state.value.candidates.first(), label = "", defaultBody = "x")
        vm.confirmDraft()
        assertEquals(0, vm.state.value.reminders.size)
        assertEquals(0, scheduler.scheduled.size)
    }

    @Test
    fun `deleting a reminder cancels its alarms and removes it`() = runTest {
        vm.onImageCaptured(ByteArray(0))
        vm.selectCandidate(vm.state.value.candidates.first(), label = "L", defaultBody = "b")
        vm.confirmDraft()
        val reminder = vm.state.value.reminders.first()

        vm.deleteReminder(reminder)

        assertEquals(0, vm.state.value.reminders.size)
        assertEquals(1, scheduler.cancelled.size)
        assertEquals(reminder.id, scheduler.cancelled.first().id)
    }

    @Test
    fun `selecting a preset seeds the draft with its suggested deadline`() = runTest {
        val sunBucks = vm.presets.first { it.programId == DeadlinePrograms.SUN_BUCKS }
        vm.selectPreset(sunBucks, label = "SUN Bucks", defaultBody = "b")
        assertEquals(LocalDate.of(2026, 8, 31), vm.state.value.draft?.deadline)
        assertEquals(DeadlinePrograms.SUN_BUCKS, vm.state.value.draft?.programId)
    }

    @Test
    fun `ocr failure surfaces an error, no candidates`() = runTest {
        recognizer.result = OcrResult.Failure("model unavailable")
        vm.onImageCaptured(ByteArray(0))
        assertEquals("model unavailable", vm.state.value.error)
        assertTrue(vm.state.value.candidates.isEmpty())
    }

    // ── Fakes ──────────────────────────────────────────────────────────

    private class FakeRecognizer(var result: OcrResult) : OcrTextRecognizer {
        override suspend fun recognize(image: OcrImage): OcrResult = result
    }

    private class FakeStore : DeadlineReminderStore {
        private val flow = MutableStateFlow<List<DeadlineReminder>>(emptyList())
        override fun reminders(): Flow<List<DeadlineReminder>> = flow
        override suspend fun all(): List<DeadlineReminder> = flow.value
        override suspend fun upsert(reminder: DeadlineReminder) {
            flow.value = flow.value.filterNot { it.id == reminder.id } + reminder
        }
        override suspend fun delete(id: String) {
            flow.value = flow.value.filterNot { it.id == id }
        }
    }

    private class FakeScheduler : DeadlineReminderScheduler {
        val scheduled = mutableListOf<DeadlineReminder>()
        val cancelled = mutableListOf<DeadlineReminder>()
        var exact = true
        override fun schedule(reminder: DeadlineReminder) { scheduled += reminder }
        override fun cancel(reminder: DeadlineReminder) { cancelled += reminder }
        override fun rescheduleAll(reminders: List<DeadlineReminder>) { scheduled += reminders }
        override fun canScheduleExact(): Boolean = exact
    }
}
