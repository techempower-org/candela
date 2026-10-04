package `in`.jphe.storyvox.feature.sync

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import `in`.jphe.storyvox.sync.client.InstantSession
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncCoordinator
import `in`.jphe.storyvox.sync.coordinator.SyncStatus

/**
 * Issue #500 — drives the persistent cloud-icon affordance in the
 * Library top-app-bar and the bottom-sheet status surface behind it.
 *
 * The three icon states map to:
 *  - [SyncIndicator.SignedIn] — session has a non-null user AND no
 *    syncer is currently running. Steady checkmark.
 *  - [SyncIndicator.Syncing] — session signed in AND at least one
 *    domain syncer is in [SyncStatus.Running]. Animated spinner
 *    overlay on the cloud icon.
 *  - [SyncIndicator.SignedOut] — session is null. Question-mark
 *    overlay; tap routes the user to the sign-in surface.
 *
 * The combine across [InstantSession.signedIn] and
 * [SyncCoordinator.status] means the icon flips in real time as a
 * push or pull starts/stops — no manual refresh hook.
 *
 * Why this is a ViewModel and not just two flows passed in: Hilt
 * scoping. Mounting [SyncCloudIcon] in the LibraryScreen's TopAppBar
 * needs an injector to resolve [InstantSession] and [SyncCoordinator],
 * which live in `:core-sync`. Routing through a Hilt-aware VM is the
 * idiomatic seam and keeps the LibraryScreen call site to a single
 * `hiltViewModel()` line.
 */
@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val session: InstantSession,
    private val coordinator: SyncCoordinator,
) : ViewModel() {

    val indicator: StateFlow<SyncIndicator> = combine(
        session.signedIn,
        coordinator.status,
    ) { user, status ->
        deriveIndicator(user, status)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        deriveIndicator(session.current(), emptyMap()),
    )

    val domainStatuses: StateFlow<Map<String, SyncStatus>> = coordinator.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val signedInUser: StateFlow<SignedInUser?> = session.signedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), session.current())

    /** Pure mapper — extracted so the unit test can exercise the
     *  state-table without booting the VM. */
    companion object {
        fun deriveIndicator(
            user: SignedInUser?,
            status: Map<String, SyncStatus>,
        ): SyncIndicator {
            if (user == null) return SyncIndicator.SignedOut
            val anyRunning = status.values.any { it is SyncStatus.Running }
            return if (anyRunning) SyncIndicator.Syncing else SyncIndicator.SignedIn
        }
    }
}

/**
 * Three-state indicator for the cloud-icon affordance — mirrors the
 * issue's section 2 spec ("cloud-with-checkmark / cloud-with-question-
 * mark / cloud-with-spinner").
 *
 * A sealed enum (not a plain enum class) so any future state with
 * payload — for example a [SignedInError] case carrying a message —
 * can be added without breaking the exhaustiveness checks on every
 * call site.
 */
sealed class SyncIndicator {
    /** Cloud-with-checkmark. Session valid AND no domain currently
     *  pulling/pushing. */
    object SignedIn : SyncIndicator()
    /** Cloud-with-spinner. Session valid AND at least one domain
     *  running. */
    object Syncing : SyncIndicator()
    /** Cloud-with-question-mark. No session. Tap → onboarding card
     *  re-open path. */
    object SignedOut : SyncIndicator()
}
