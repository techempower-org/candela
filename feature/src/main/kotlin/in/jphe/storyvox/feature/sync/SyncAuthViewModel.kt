package `in`.jphe.storyvox.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.sync.client.InstantClient
import `in`.jphe.storyvox.sync.client.InstantSession
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.client.SyncAuthResult
import `in`.jphe.storyvox.sync.coordinator.SyncCoordinator
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state machine for the sync sign-in screen.
 *
 * State graph:
 *   SignedOut (idle, email entry)
 *     → SendingCode
 *       → CodePrompt (code entry)
 *         → Verifying
 *           → SignedIn (success — calls coordinator to push existing local state)
 *         → CodePrompt (with [SignInState.CodePrompt.error] for a bad code)
 *       → SignedOut (with error)
 *
 * The flow is one screen — fields appear progressively as state
 * advances. The coordinator's `requestPushAll()` is fired on
 * successful sign-in to upload existing on-device state (the
 * migration story).
 */
@HiltViewModel
class SyncAuthViewModel @Inject constructor(
    private val client: InstantClient,
    private val session: InstantSession,
    private val coordinator: SyncCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow<SignInState>(initialState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private fun initialState(): SignInState =
        session.current()?.let { SignInState.SignedIn(it) } ?: SignInState.SignedOut(email = "")

    fun updateEmail(email: String) {
        val current = _state.value as? SignInState.SignedOut ?: return
        _state.value = current.copy(email = email, error = null)
    }

    fun updateCode(code: String) {
        val current = _state.value as? SignInState.CodePrompt ?: return
        _state.value = current.copy(code = code, error = null)
    }

    /** Email → "send the code." Triggers a transition to [SignInState.SendingCode]
     *  while the network call is in flight.
     *
     *  Issue #583 — client-side validation before we ever hit InstantDB.
     *  The previous gate (`!email.contains('@')`) accepted obvious junk
     *  like 200-char `aaaa...@b.c` strings, and the server then leaked
     *  its raw parser error (`Malformed parameter: [\`) into the user-
     *  visible field caption. Validating up front means the common
     *  garbage cases short-circuit with a friendly message, and any
     *  server error that does slip through gets sanitized by
     *  [sanitizeAuthError] so the user never sees raw JSON-path tokens. */
    fun sendCode() {
        val current = _state.value as? SignInState.SignedOut ?: return
        val email = current.email.trim()
        if (!isLikelyEmail(email)) {
            _state.value = current.copy(error = "Enter a valid email address")
            return
        }
        _state.value = SignInState.SendingCode(email)
        viewModelScope.launch {
            when (val result = client.sendMagicCode(email)) {
                is SyncAuthResult.Ok -> {
                    _state.value = SignInState.CodePrompt(email = email, code = "", error = null)
                }
                is SyncAuthResult.Err -> {
                    _state.value = SignInState.SignedOut(
                        email = email,
                        error = sanitizeAuthError(result.message),
                    )
                }
            }
        }
    }

    /** Code → "verify it." On success, persists the refresh token and
     *  kicks off the post-sign-in push migration. */
    fun verifyCode() {
        val current = _state.value as? SignInState.CodePrompt ?: return
        val code = current.code.trim()
        if (code.length < 4) {
            _state.value = current.copy(error = "Enter the 6-digit code")
            return
        }
        _state.value = SignInState.Verifying(current.email, code)
        viewModelScope.launch {
            when (val result = client.verifyMagicCode(current.email, code)) {
                is SyncAuthResult.Ok -> {
                    session.store(result.value)
                    val signedIn = SignedInUser(
                        userId = result.value.id,
                        email = result.value.email,
                        refreshToken = result.value.refreshToken,
                    )
                    _state.value = SignInState.SignedIn(signedIn)
                    // Migration: push existing local state up so a new
                    // device pulls it back. Fire-and-forget on the
                    // coordinator's own scope — we don't block the UI.
                    coordinator.requestPushAll()
                }
                is SyncAuthResult.Err -> {
                    _state.value = SignInState.CodePrompt(
                        email = current.email,
                        code = current.code,
                        error = sanitizeAuthError(result.message),
                    )
                }
            }
        }
    }

    /**
     * Sign out: revoke the refresh token server-side, then wipe local
     * credentials. Cloud data is intentionally PRESERVED.
     *
     * Issue #1248 — sign-out and "purge cloud data" are now separate
     * actions. Sign-out used to delete the user's InstantDB record first
     * (#1139), so there was no way to sign out on one device while keeping
     * the cloud copy for the others, nor to sign out at all without losing
     * everything. Deleting cloud data is now an explicit, separately-
     * confirmed action — see [purgeRemoteData]. After signing out the user
     * can sign back in and pull their cloud data down again.
     *
     * Both remaining steps are best-effort: an offline sign-out still
     * completes locally (the user shouldn't be trapped signed-in). Local
     * on-device library/positions are intentionally kept — see
     * [SyncCoordinator]'s class kdoc.
     *
     * Issue #1217 — the local wipe ([InstantSession.clear] + the state
     * reset) runs in a [NonCancellable] `finally`, so a cancellation of
     * this coroutine mid-sign-out (e.g. the ViewModel being cleared as the
     * user navigates away) can't strand a half-signed-out state where the
     * token is revoked server-side but the device still holds it and
     * renders as signed-in. The cloud-side [InstantClient.signOut] call
     * stays cancellable (it's best-effort); only the local wipe is guarded.
     */
    fun signOut() {
        val current = session.current() ?: run {
            _state.value = SignInState.SignedOut(email = "")
            return
        }
        viewModelScope.launch {
            try {
                client.signOut(current.refreshToken) // best-effort; ignored on failure
            } finally {
                // #1217 — local wipe must complete even if the coroutine is
                // cancelled during the cloud call above, or we leave a
                // half-signed-out state.
                withContext(NonCancellable) {
                    session.clear()
                    _state.value = SignInState.SignedOut(email = "")
                }
            }
        }
    }

    /**
     * Issue #1248 — explicitly delete the user's cloud (InstantDB) record
     * WITHOUT signing out. Separate from [signOut] so a user can delete
     * their cloud data while keeping their local session (and can sign out
     * on one device without affecting the cloud copy other devices rely
     * on).
     *
     * Delegates to [SyncCoordinator.purgeRemoteData], which deletes every
     * domain's remote row using the still-valid refresh token (the admin
     * delete API authorizes via as-token impersonation). Best-effort by
     * design — the coordinator returns false if any domain failed, and the
     * privacy policy's email-request path backstops a partial failure.
     *
     * Progress + outcome are surfaced via [SignInState.SignedIn.purge] so
     * the screen can show a loading indicator while the network calls run
     * and a success/error result afterward. The local session is untouched
     * — the user stays signed in throughout.
     */
    fun purgeRemoteData() {
        val current = session.current() ?: run {
            _state.value = SignInState.SignedOut(email = "")
            return
        }
        val signedIn = _state.value as? SignInState.SignedIn ?: return
        _state.value = signedIn.copy(purge = PurgeState.Running)
        viewModelScope.launch {
            val ok = coordinator.purgeRemoteData(current)
            // Only publish the outcome if we're still on the signed-in
            // screen — the user may have signed out / navigated away while
            // the network calls were in flight.
            (_state.value as? SignInState.SignedIn)?.let { now ->
                _state.value = now.copy(
                    purge = if (ok) PurgeState.Success else PurgeState.Error,
                )
            }
        }
    }

    /** Reset to email-entry from any state (e.g. user wants a fresh code). */
    fun reset() {
        _state.value = SignInState.SignedOut(email = "")
    }
}

sealed interface SignInState {
    data class SignedOut(val email: String, val error: String? = null) : SignInState
    data class SendingCode(val email: String) : SignInState
    data class CodePrompt(val email: String, val code: String, val error: String?) : SignInState
    data class Verifying(val email: String, val code: String) : SignInState
    data class SignedIn(
        val user: SignedInUser,
        val purge: PurgeState = PurgeState.Idle,
    ) : SignInState
}

/**
 * Issue #1248 — progress/outcome of an explicit "purge cloud data" action,
 * tracked on [SignInState.SignedIn] so the screen can render a loading
 * indicator while the coordinator's network calls run and a success/error
 * result afterward without leaving the signed-in screen. [Error] carries
 * no message: [SyncCoordinator.purgeRemoteData] is a best-effort boolean,
 * so the screen shows a generic retry prompt.
 */
sealed interface PurgeState {
    data object Idle : PurgeState
    data object Running : PurgeState
    data object Success : PurgeState
    data object Error : PurgeState
}

/**
 * Issue #583 — client-side email validation. Intentionally permissive
 * (we don't want to reject valid-but-unusual addresses like `a@b.c`
 * or `user+tag@sub.example.museum`) while still catching the garbage
 * inputs the stress test surfaced: empty strings, missing `@`, 184-
 * char `aaaa...@b.c` runs, whitespace inside the local part, etc.
 *
 * The rules (anchored on RFC 5321 §4.5.3.1):
 *  1. Total length within RFC 5321's 254-char practical max.
 *  2. Local part: 1..64 chars (RFC §4.5.3.1.1), no whitespace,
 *     no commas/semicolons (the InstantDB JSON-parser's trigger
 *     tokens — the stress test's 180-a's input crashed past every
 *     other gate because every char individually looked fine).
 *  3. Exactly one `@`.
 *  4. Domain part: 1..253 chars (effectively bounded by the total
 *     254 cap minus the local + `@`), contains a `.`, no whitespace.
 *
 * Deliberately NOT a full RFC 5322 regex — that's a known footgun and
 * the server still validates authoritatively. This guard exists to
 * stop the obvious cases from reaching the server's raw error path.
 *
 * Exposed `internal` so the companion test can pin the spec without
 * standing up the VM coroutine harness — same pattern as
 * [shouldShowOnboarding] for the onboarding gate.
 */
internal fun isLikelyEmail(raw: String): Boolean {
    val email = raw.trim()
    if (email.isEmpty() || email.length > 254) return false
    val atIdx = email.indexOf('@')
    if (atIdx <= 0 || atIdx != email.lastIndexOf('@')) return false
    val local = email.substring(0, atIdx)
    val domain = email.substring(atIdx + 1)
    if (local.isEmpty() || domain.isEmpty()) return false
    // RFC 5321 §4.5.3.1.1: local part maximum 64 octets. The 180-a's
    // stress-test input fails here — without this cap a 184-char
    // address slips past `length > 254` and reaches InstantDB.
    if (local.length > 64) return false
    if (local.any { it.isWhitespace() || it == ',' || it == ';' }) return false
    if (domain.any { it.isWhitespace() || it == ',' || it == ';' }) return false
    // Domain must contain a `.` and the dot can't be first or last.
    val dotIdx = domain.indexOf('.')
    if (dotIdx <= 0 || dotIdx == domain.lastIndex) return false
    return true
}

/**
 * Issue #583 — sanitize server-side auth errors before showing them
 * to the user. The InstantDB endpoint returns its parser error
 * verbatim on malformed payloads (e.g. `Malformed parameter: [\`),
 * and that raw string contains JSON-path tokens (`[`, `]`, `\`, `"`)
 * that confuse users and look like a bug, not a validation failure.
 *
 * Strategy:
 *  1. Drop trailing dangling JSON-path tokens (`[`, `]`, `\`, `,`).
 *  2. If the truncated string is now empty / all-punctuation / looks
 *     like a JSON fragment, fall back to a friendly generic message
 *     keyed off the most common cases (malformed parameter → email
 *     issue, otherwise generic).
 *  3. Cap length at 140 chars so a paragraph-long server message
 *     doesn't blow out the field caption row.
 *
 * Pure / no Android deps so the unit test can pin every branch
 * without a Robolectric harness.
 */
internal fun sanitizeAuthError(raw: String?): String {
    val fallback = "Couldn't send the sign-in code. Please check the email address and try again."
    if (raw.isNullOrBlank()) return fallback
    var msg = raw.trim()
    // Trim trailing JSON-structure punctuation that leaks parser state.
    msg = msg.trimEnd(' ', '[', ']', '\\', ',', ':', '"', '\'')
    // If the message is now empty or starts to look like JSON noise,
    // map to a friendly equivalent.
    if (msg.isBlank()) return fallback
    val lower = msg.lowercase()
    return when {
        lower.startsWith("malformed parameter") ||
            lower.contains("invalid email") ||
            lower.contains("not a valid email") ->
            "That email address doesn't look right. Please double-check and try again."
        lower.contains("rate limit") || lower.contains("too many requests") ->
            "Too many attempts. Wait a minute and try again."
        lower.contains("network") || lower.contains("unable to resolve") || lower.contains("timeout") ->
            "Couldn't reach the sync server. Check your connection and try again."
        msg.length > 140 -> msg.substring(0, 137) + "…"
        else -> msg
    }
}
