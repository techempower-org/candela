package `in`.jphe.storyvox.auth.googledrive

import `in`.jphe.storyvox.data.GoogleDriveConfigImpl
import `in`.jphe.storyvox.feature.api.GoogleDriveConnector
import `in`.jphe.storyvox.feature.api.GoogleDriveConnectorState
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Issue #1677 — production [GoogleDriveConnector]: glues the OAuth manager
 * (connect), the Picker page (choose files) and the encrypted session
 * (disconnect) into the one seam Browse talks to.
 */
@Singleton
class GoogleDriveConnectorImpl @Inject constructor(
    private val manager: GoogleDriveOAuthManager,
    private val config: GoogleDriveConfigImpl,
    private val oauthApi: GoogleDriveOAuthApi,
) : GoogleDriveConnector {

    private val pickTick = MutableStateFlow(0L)

    override val state: Flow<GoogleDriveConnectorState> =
        combine(config.state, pickTick) { session, tick ->
            GoogleDriveConnectorState(
                oauthAvailable = GoogleDriveOAuthConfig.isAvailable,
                pickerAvailable = GoogleDrivePickerConfig.isAvailable,
                connected = session.connected,
                pickGeneration = tick,
            )
        }.distinctUntilChanged()

    override suspend fun beginConnect(): String? = manager.beginConnect()

    override suspend fun pickerUrl(): String? {
        if (!GoogleDrivePickerConfig.isAvailable) return null
        // The page needs a token that lives long enough to browse + pick.
        val token = config.freshAccessToken().ifBlank { null } ?: return null
        return GoogleDrivePickerConfig.pickerUrl(token, Locale.getDefault().toLanguageTag())
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            // Revoke the refresh token when we have one (revokes the whole
            // grant); else the access token. Best-effort — clear regardless.
            val token = config.refreshTokenValue() ?: config.current().accessToken
            oauthApi.revoke(token)
            config.clear()
        }
    }

    /** Called by MainActivity when the Picker page returns. */
    fun onPickerReturned() {
        pickTick.value = pickTick.value + 1
    }
}
