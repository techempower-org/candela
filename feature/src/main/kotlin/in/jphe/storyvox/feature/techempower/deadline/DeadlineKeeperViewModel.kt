package `in`.jphe.storyvox.feature.techempower.deadline

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.ocr.OcrImage
import `in`.jphe.storyvox.data.ocr.OcrResult
import `in`.jphe.storyvox.data.ocr.OcrTextRecognizer
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Issue #1515 — drives the notice deadline keeper.
 *
 * Flow: the screen hands a captured / picked photo of a notice to
 * [onImageCaptured]; this VM runs it through the on-device
 * [OcrTextRecognizer] (the same bundled ML Kit seam as #995 — no
 * network, works in airplane mode) and extracts date candidates with the
 * pure [DeadlineDateExtractor]. The user confirms one (never auto-
 * committed — see [selectCandidate]), edits the reminder copy, and
 * [confirmDraft] persists it via [DeadlineReminderStore] and arms the
 * local notifications via [DeadlineReminderScheduler].
 *
 * All Android specifics stay behind the injected seams, so this VM is
 * plain JVM logic and unit-testable with fakes (no Robolectric).
 */
@HiltViewModel
class DeadlineKeeperViewModel @Inject constructor(
    private val recognizer: OcrTextRecognizer,
    private val store: DeadlineReminderStore,
    private val scheduler: DeadlineReminderScheduler,
    private val clock: DeadlineClock,
) : ViewModel() {

    private val _state = MutableStateFlow(
        DeadlineKeeperUiState(exactAlarmsAllowed = scheduler.canScheduleExact()),
    )
    val state: StateFlow<DeadlineKeeperUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.reminders().collect { list ->
                _state.update { it.copy(reminders = list.sortedBy { r -> r.deadlineEpochDay }) }
            }
        }
    }

    /** The seed recurring-deadline presets to offer in the picker. */
    val presets: List<DeadlineRecertPreset> get() = DeadlineRecertPresets.SEED

    /** Recognize text in a captured / picked notice and extract candidate dates. */
    fun onImageCaptured(bytes: ByteArray, rotationDegrees: Int = 0) {
        if (_state.value.isRecognizing) return
        _state.update { it.copy(isRecognizing = true, error = null, noDatesFound = false) }
        viewModelScope.launch {
            when (val result = recognizer.recognize(OcrImage(bytes, rotationDegrees))) {
                is OcrResult.Failure -> _state.update {
                    it.copy(isRecognizing = false, error = result.message)
                }

                is OcrResult.Success -> {
                    val candidates = DeadlineDateExtractor.extract(
                        result.recognition.text,
                        clock.today(),
                    )
                    _state.update {
                        it.copy(
                            isRecognizing = false,
                            candidates = candidates,
                            noDatesFound = candidates.isEmpty(),
                        )
                    }
                }
            }
        }
    }

    /**
     * User picked a scanned candidate → open the editable draft. [label]
     * and [defaultBody] are localized defaults supplied by the screen so
     * copy stays bilingual (the VM never hardcodes user-facing text).
     */
    fun selectCandidate(candidate: DateCandidate, label: String, defaultBody: String) {
        _state.update {
            it.copy(
                draft = ReminderDraft(
                    programId = null,
                    label = label,
                    body = defaultBody,
                    deadline = candidate.date,
                    offsetsDays = DeadlineReminder.DEFAULT_OFFSETS_DAYS,
                ),
                candidates = emptyList(),
                noDatesFound = false,
            )
        }
    }

    /** User picked a recurring-deadline preset → open the editable draft. */
    fun selectPreset(preset: DeadlineRecertPreset, label: String, defaultBody: String) {
        _state.update {
            it.copy(
                draft = ReminderDraft(
                    programId = preset.programId,
                    label = label,
                    body = defaultBody,
                    deadline = preset.suggestedDeadline(clock.today()),
                    offsetsDays = DeadlineReminder.DEFAULT_OFFSETS_DAYS,
                ),
            )
        }
    }

    fun onDraftLabelChanged(label: String) = updateDraft { it.copy(label = label) }
    fun onDraftBodyChanged(body: String) = updateDraft { it.copy(body = body) }
    fun onDraftDeadlineChanged(date: LocalDate) = updateDraft { it.copy(deadline = date) }
    fun onDraftOffsetsChanged(offsets: List<Int>) =
        updateDraft { it.copy(offsetsDays = offsets.distinct().sortedDescending()) }

    private inline fun updateDraft(transform: (ReminderDraft) -> ReminderDraft) {
        _state.update { s -> s.draft?.let { s.copy(draft = transform(it)) } ?: s }
    }

    /** Discard the in-progress draft without scheduling. */
    fun cancelDraft() = _state.update { it.copy(draft = null) }

    /**
     * Persist + schedule the current draft. No-op if there's no draft or
     * the label is blank (the confirm affordance is disabled in that
     * case, but guard anyway).
     */
    fun confirmDraft() {
        val draft = _state.value.draft ?: return
        if (draft.label.isBlank()) return
        val reminder = DeadlineReminder(
            id = UUID.randomUUID().toString(),
            programId = draft.programId,
            label = draft.label.trim(),
            deadlineEpochDay = draft.deadline.toEpochDay(),
            notificationTitle = draft.label.trim(),
            notificationBody = draft.body.trim().ifBlank { draft.label.trim() },
            offsetsDays = draft.offsetsDays.ifEmpty { DeadlineReminder.DEFAULT_OFFSETS_DAYS },
            createdEpochDay = clock.today().toEpochDay(),
        )
        viewModelScope.launch {
            store.upsert(reminder)
            scheduler.schedule(reminder)
            _state.update {
                it.copy(
                    draft = null,
                    justScheduled = true,
                    exactAlarmsAllowed = scheduler.canScheduleExact(),
                )
            }
        }
    }

    /** Remove a scheduled reminder + cancel its pending notifications. */
    fun deleteReminder(reminder: DeadlineReminder) {
        viewModelScope.launch {
            scheduler.cancel(reminder)
            store.delete(reminder.id)
        }
    }

    /** Re-read the exact-alarm grant (call when returning from settings). */
    fun refreshExactAlarmState() =
        _state.update { it.copy(exactAlarmsAllowed = scheduler.canScheduleExact()) }

    fun clearError() = _state.update { it.copy(error = null) }
    fun consumeScheduledSignal() = _state.update { it.copy(justScheduled = false) }
    fun dismissNoDates() = _state.update { it.copy(noDatesFound = false) }
}

/** UI state for the deadline keeper screen. */
@Immutable
data class DeadlineKeeperUiState(
    val isRecognizing: Boolean = false,
    val candidates: List<DateCandidate> = emptyList(),
    val noDatesFound: Boolean = false,
    val draft: ReminderDraft? = null,
    val reminders: List<DeadlineReminder> = emptyList(),
    val error: String? = null,
    /** One-shot: a reminder was just scheduled (drives an a11y announce + toast). */
    val justScheduled: Boolean = false,
    /** False when the OS is blocking exact alarms (Android 12+); UI nudges to settings. */
    val exactAlarmsAllowed: Boolean = true,
)

/** An in-progress, user-editable reminder before it's confirmed. */
@Immutable
data class ReminderDraft(
    val programId: String?,
    val label: String,
    val body: String,
    val deadline: LocalDate,
    val offsetsDays: List<Int>,
) {
    val canConfirm: Boolean get() = label.isNotBlank()
}
