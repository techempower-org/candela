package `in`.jphe.storyvox.feature.docs.profile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Issue #1519 — drives the saved household profile screen.
 *
 * Loads the encrypted-at-rest profile, edits an in-memory [ReminderDraft]-
 * style draft, and persists / clears it via [HouseholdProfileStore]. The
 * biometric / device-credential gate for edit + delete lives in the screen
 * (it needs an Activity result launcher); this VM is plain-JVM and
 * unit-testable with a fake store.
 */
@HiltViewModel
class HouseholdProfileViewModel @Inject constructor(
    private val store: HouseholdProfileStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HouseholdProfileUiState())
    val state: StateFlow<HouseholdProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.profile().collect { saved ->
                _state.update { s ->
                    // Keep the user's in-progress edits; only re-sync the
                    // draft from the store when it wasn't diverged.
                    if (s.draft == s.saved) s.copy(saved = saved, draft = saved)
                    else s.copy(saved = saved)
                }
            }
        }
    }

    fun onFieldChange(field: ProfileField, value: String) =
        _state.update { it.copy(draft = it.draft.with(field, value)) }

    fun save() {
        val draft = _state.value.draft
        viewModelScope.launch {
            store.save(draft)
            _state.update { it.copy(saved = draft, justSaved = true) }
        }
    }

    /** Revert unsaved edits back to what's stored. */
    fun revert() = _state.update { it.copy(draft = it.saved) }

    fun deleteProfile() {
        viewModelScope.launch {
            store.clear()
            _state.update { it.copy(draft = HouseholdProfile(), saved = HouseholdProfile()) }
        }
    }

    fun consumeJustSaved() = _state.update { it.copy(justSaved = false) }
}

@Immutable
data class HouseholdProfileUiState(
    val draft: HouseholdProfile = HouseholdProfile(),
    val saved: HouseholdProfile = HouseholdProfile(),
    /** One-shot: a save just completed (drives an a11y / toast confirmation). */
    val justSaved: Boolean = false,
) {
    val hasSavedProfile: Boolean get() = !saved.isEmpty
    val hasUnsavedChanges: Boolean get() = draft != saved
}
