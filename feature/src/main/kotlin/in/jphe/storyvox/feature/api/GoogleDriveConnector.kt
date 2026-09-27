package `in`.jphe.storyvox.feature.api

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1677 — the Browse-facing seam for the Google Drive integration:
 * connect (OAuth), choose files (Google Picker), disconnect. Lives in
 * `:feature` so the UI never depends on `:app`; the production
 * implementation (`in.jphe.storyvox.auth.googledrive.GoogleDriveConnectorImpl`)
 * is bound in `AppBindings`.
 *
 * A dedicated interface rather than more `SettingsRepositoryUi` methods so
 * the Drive surface doesn't grow the shared settings contract (and its many
 * hand-rolled test fakes).
 */
interface GoogleDriveConnector {

    /** Hot connection snapshot for the Browse Drive chip. */
    val state: Flow<GoogleDriveConnectorState>

    /** Start OAuth. Returns the authorize URL for a Custom Tab, or null
     *  when this build has no OAuth client id. */
    suspend fun beginConnect(): String?

    /** A Picker URL carrying a fresh access token, or null when the build
     *  lacks the Picker key/app id or the session is gone. */
    suspend fun pickerUrl(): String?

    /** Revoke (best-effort) and forget the Drive session. */
    suspend fun disconnect()
}

/**
 * @property oauthAvailable the build carries an OAuth client id (Connect works).
 * @property pickerAvailable the build can also open the Google Picker.
 * @property connected a Drive session is stored.
 * @property pickGeneration bumped each time the user returns from the Picker,
 *  so Browse can re-list the newly granted files.
 */
data class GoogleDriveConnectorState(
    val oauthAvailable: Boolean = false,
    val pickerAvailable: Boolean = false,
    val connected: Boolean = false,
    val pickGeneration: Long = 0L,
)
