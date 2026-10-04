package `in`.jphe.storyvox.sync.client

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the InstantDB refresh token + user info, and exposes a Flow so
 * the rest of the app can observe sign-in state without polling.
 *
 * The refresh token is the bearer credential for the user's data — equal in
 * sensitivity to an OAuth access token. It lives in the same
 * EncryptedSharedPreferences bag as the rest of storyvox's secrets
 * (LLM API keys, RR cookies) so a single Tink-backed master key protects
 * the lot. See `:core-data DataModule.provideEncryptedPrefs`.
 *
 * One user per device — the [signedIn] state is `null` if no one is signed
 * in, and a [SignedInUser] otherwise. We don't model multi-user.
 */
class InstantSession internal constructor(
    private val secrets: SharedPreferences,
) {
    private val _state = MutableStateFlow(readFromPrefs())
    val signedIn: StateFlow<SignedInUser?> = _state

    /** Persist a fresh sign-in. Emits on the [signedIn] flow synchronously
     *  so observers can react before the next coroutine dispatch. */
    fun store(user: InstantUser) {
        val signed = SignedInUser(
            userId = user.id,
            email = user.email,
            refreshToken = user.refreshToken,
        )
        secrets.edit().apply {
            putString(KEY_REFRESH_TOKEN, signed.refreshToken)
            putString(KEY_USER_ID, signed.userId)
            putString(KEY_EMAIL, signed.email)
            apply()
        }
        _state.value = signed
    }

    /** Wipe everything. The caller is responsible for any server-side
     *  sign-out call before this clears the token. */
    fun clear() {
        secrets.edit().apply {
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_USER_ID)
            remove(KEY_EMAIL)
            apply()
        }
        _state.value = null
    }

    /** Synchronous accessor for code paths that can't suspend. */
    fun current(): SignedInUser? = _state.value

    private fun readFromPrefs(): SignedInUser? {
        val token = secrets.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val userId = secrets.getString(KEY_USER_ID, null) ?: return null
        return SignedInUser(
            userId = userId,
            email = secrets.getString(KEY_EMAIL, null),
            refreshToken = token,
        )
    }

    private companion object {
        const val KEY_REFRESH_TOKEN = "instantdb.refresh_token"
        const val KEY_USER_ID = "instantdb.user_id"
        const val KEY_EMAIL = "instantdb.email"
    }
}

/** What the app knows about the signed-in user. */
data class SignedInUser(
    val userId: String,
    val email: String?,
    val refreshToken: String,
)
