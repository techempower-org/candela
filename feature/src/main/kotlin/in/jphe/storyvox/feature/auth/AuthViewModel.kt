package `in`.jphe.storyvox.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.auth.SessionState
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Sits between the WebView capture path and the persisted session state.
 *
 * The WebView (in :source-royalroad or :source-ao3) hands us the captured
 * cookie map. We:
 * 1. Build the canonical `Cookie:` header string and stash it in
 *    EncryptedSharedPreferences via [AuthRepository.captureSession].
 * 2. Push the same cookies into the live OkHttp jar via the per-source
 *    [SessionHydrator] map so the next browse / chapter fetch is authed
 *    without restarting the app.
 * 3. Flip the UI sign-in flag in DataStore via [SettingsRepositoryUi] so
 *    the Settings screen rerenders the "Sign out" button immediately.
 *
 * [captureState] flips to [CaptureState.Captured] once both stores are
 * written; the screen observes it to know when to pop the back stack.
 *
 * #426 PR2 — [captureCookies] now takes a `sourceId` so AO3 sign-in
 * routes through the AO3 hydrator and writes the AO3 row in the
 * encrypted cookie store. The previous TODO(PR2 / #426) placeholder
 * is resolved. Backwards-compat: callers that omit `sourceId` get
 * the legacy Royal Road behaviour bit-identically.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    /**
     * Per-source hydrator map keyed by [SourceIds]. The legacy single
     * [SessionHydrator] binding is kept for non-AuthViewModel callers
     * (StoryvoxApp's eager hydration on cold start, Settings sign-out);
     * this map is the cross-source dispatch surface the WebView capture
     * path uses post-#426 PR2.
     */
    private val hydrators: Map<String, @JvmSuppressWildcards SessionHydrator>,
    private val settings: SettingsRepositoryUi,
    private val fictionRepo: FictionRepository,
) : ViewModel() {

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    /** #1592 — AO3 signed-in state for the Account screen row, observed off
     *  [AuthRepository.sessionState] (the same source Browse reads for its
     *  auth-only AO3 tabs). */
    val ao3SignedIn: StateFlow<Boolean> = auth.sessionState(SourceIds.AO3)
        .map { it is SessionState.Authenticated }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** #1592 — sign out a web-session source: clear the stored cookie header
     *  (AuthRepository) AND the live OkHttp jar (per-source hydrator), mirroring
     *  SettingsRepositoryUiImpl.signOut() for Royal Road. Without the jar clear
     *  the source keeps sending the stale session cookie until app restart. */
    fun signOut(sourceId: String) = viewModelScope.launch {
        auth.clearSession(sourceId)
        hydrators[sourceId]?.clear()
    }

    /**
     * Persist the captured cookie map for [sourceId].
     *
     * The `cookies` map may carry an extra
     * [`USERNAME_KEY`][in.jphe.storyvox.source.ao3.auth.USERNAME_KEY]
     * entry (AO3's WebView stashes the username it scraped from the
     * post-login redirect URL there — see kdoc on
     * [`Ao3AuthWebView`][in.jphe.storyvox.source.ao3.auth.Ao3AuthWebView]).
     * We split it out *before* building the cookie header so it
     * never lands in the `Cookie:` HTTP header AO3 would reject as
     * malformed. The username is persisted into the
     * `auth_cookie.userId` column for the AO3 row, where
     * [`Ao3Source`][in.jphe.storyvox.source.ao3.Ao3Source] reads it
     * back when fetching the user's subscription / Marked-for-Later
     * surfaces.
     *
     * Defaults to [SourceIds.ROYAL_ROAD] so existing RR sign-in call
     * sites (the `AuthWebViewScreen` route, the `RoyalRoadAuthWebView`
     * composable's `onSession` lambda) keep compiling and behaving
     * bit-identically — the new `sourceId` parameter is opt-in.
     */
    fun captureCookies(
        cookies: Map<String, String>,
        sourceId: String = SourceIds.ROYAL_ROAD,
    ) {
        if (cookies.isEmpty()) return
        if (_captureState.value is CaptureState.Captured) return
        _captureState.value = CaptureState.Capturing
        viewModelScope.launch {
            // Pull the AO3 username pseudo-cookie out of the captured
            // map before building the HTTP cookie header. AO3 (and
            // RR, for the symmetric path) would reject a Cookie header
            // containing `__storyvox_user=alice; …` so we strip the
            // sentinel here, persist it separately as the row's
            // userId, and pass the cleaned cookie set to both the
            // AuthRepository (Cookie header) and the SessionHydrator
            // (OkHttp jar).
            val username = cookies[USERNAME_KEY]?.takeIf { it.isNotBlank() }
            val realCookies = cookies - USERNAME_KEY
            val cookieHeader = realCookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
            auth.captureSession(
                cookieHeader = cookieHeader,
                userDisplayName = username,
                userId = username,
                expiresAt = null,
                sourceId = sourceId,
            )
            // Per-source hydrator dispatch. Defensive fallback: if the
            // map doesn't have the requested source (unlikely — the
            // module must be linked and registered), no-op rather than
            // crash. The cookie header is already persisted; the user
            // can re-trigger after the binding is in place.
            hydrators[sourceId]?.hydrate(realCookies)
            // The signed-in flag in UI settings is RR-shaped today (it
            // gates the Follows tab on Library). Only flip it for RR
            // sign-in; AO3 sign-in surfaces through the AO3 chip's
            // signed-in state observed off [AuthRepository.sessionState].
            if (sourceId == SourceIds.ROYAL_ROAD) {
                settings.signIn()
            }
            _captureState.value = CaptureState.Captured
            // Fire-and-forget: pull the user's RR follows into the local
            // DB so the Follows tab populates without an extra user
            // action. AO3's subscriptions surface is rendered live from
            // the API (not cached into FictionRepository's Follows
            // table), so this remains RR-only.
            if (sourceId == SourceIds.ROYAL_ROAD) {
                runCatching { fictionRepo.refreshRemoteFollows() }
            }
        }
    }
}

/**
 * Magic-prefix key the WebView capture path uses to thread an out-of-band
 * username through the [`captureCookies`][AuthViewModel.captureCookies]
 * `Map<String, String>` channel. The prefix `__storyvox_user` is
 * namespaced so a real source cookie can never collide with it. Mirror
 * of [`in.jphe.storyvox.source.ao3.auth.USERNAME_KEY`] — duplicated here
 * because `:feature` can't depend on `:source-ao3`.
 */
const val USERNAME_KEY: String = "__storyvox_user"

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Capturing : CaptureState
    data object Captured : CaptureState
}
