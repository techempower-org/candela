package `in`.jphe.storyvox.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.playback.voice.VoiceManager
import `in`.jphe.storyvox.sync.client.InstantSession

/**
 * Issue #500 — drives the magical first-launch InstantDB sign-in card
 * mounted at the navigation host root (alongside [MilestoneDialog]).
 *
 * The card renders when ALL of the following hold:
 *  - The user has an active voice picked ([VoiceManager.activeVoice]
 *    non-null). The issue is explicit: "after the VoicePickerGate,
 *    before landing in Library." This gate enforces the ordering so
 *    the user sees the picker FIRST, then the sync card. The picker's
 *    own "Continue without audio" bypass is session-only — a user
 *    who taps it won't see the sync card this session, which is the
 *    right behaviour (they're already in a "skip onboarding"
 *    frame of mind).
 *  - The user is signed OUT of InstantDB ([InstantSession.signedIn]
 *    is null). A user who's already signed in (e.g. an APK upgrade
 *    after the v0.5.39 surface landed in #470) never sees the
 *    onboarding offer.
 *  - The dismissed flag is false (the user has not yet dismissed or
 *    completed the flow).
 *  - The card is not in the "presenting" terminal state. Once the
 *    user taps Sign-in or Skip the flow drives itself to completion
 *    via [dismiss].
 *
 * State machine:
 *   Idle → [shouldShow] gate flips true → composed
 *   composed → [dismiss] → flag persisted → [shouldShow] flips false
 *
 * Why a separate VM (not a flag on [SyncAuthViewModel]): the
 * onboarding card is a *gate decision*, not a sign-in flow. Mounting
 * it at the NavHost root means it lives outside any single screen's
 * VM scope; mixing it into [SyncAuthViewModel] would require
 * [SyncAuthViewModel] to survive screen transitions, which Hilt's
 * default scoping doesn't promise. Cheap, explicit, isolated.
 *
 * **Future-state note (per the MEMORY.md "loud guards with kdoc"
 * playbook)**: The `signedIn == null` check is the correct gate
 * *today* because the InstantDB session and the secrets-passphrase
 * are coupled (one user = one passphrase). When the
 * passphrase-recovery flow lands (issue #500 "Out of scope (later)"),
 * we'll need a separate gate for "signed-in but no passphrase yet"
 * — the natural place is a second derived flow on this VM keyed on
 * [PassphraseProvider.get] returning null. Until then this gate's
 * simplicity is its strength.
 */
@HiltViewModel
class SyncOnboardingViewModel @Inject constructor(
    private val settings: SettingsRepositoryUi,
    private val session: InstantSession,
    private val voices: VoiceManager,
) : ViewModel() {

    private val _userTriggeredOpen = MutableStateFlow(false)

    /** True iff the onboarding card should be rendered right now.
     *  Auto-opens the FIRST time a signed-out user with the dismissed
     *  flag still false AND an active voice picked lands on the app;
     *  can also be re-opened manually via [openManually] (e.g.
     *  tapping "Set up sync" from the cloud-icon sheet). The voice-
     *  picked gate enforces the issue's "after VoicePickerGate"
     *  ordering — without it the dialog floats over the picker
     *  before the user finishes the previous step. */
    val shouldShow: StateFlow<Boolean> = combine(
        settings.syncOnboardingDismissed,
        session.signedIn,
        voices.activeVoice,
        _userTriggeredOpen,
    ) { dismissed, signedIn, activeVoice, manuallyOpened ->
        // Manual open beats every gate — the user explicitly asked.
        if (manuallyOpened) return@combine true
        // First-launch path: voice picked, signed out, not dismissed.
        activeVoice != null && signedIn == null && !dismissed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Persist the dismissed flag and clear any manual-open trigger.
     *  Called on every onboarding-card exit path (Skip, Sign-in tap,
     *  outside-tap). The dismissal is permanent — the card never
     *  re-prompts on a future launch per the issue. */
    fun dismiss() {
        viewModelScope.launch {
            settings.markSyncOnboardingDismissed()
            _userTriggeredOpen.value = false
        }
    }

    /** Open the onboarding card from a non-first-launch entry point
     *  (e.g. cloud-icon → "Learn about sync"). This bypasses the
     *  dismissed flag for one viewing but doesn't reset it — the
     *  user can re-dismiss with no side effects. */
    fun openManually() {
        _userTriggeredOpen.value = true
    }
}
