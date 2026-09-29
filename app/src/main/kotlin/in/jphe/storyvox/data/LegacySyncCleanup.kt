package `in`.jphe.storyvox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Issue #1821 — Candela removed its InstantDB cloud sync. Installs that
 * used sync still carry its on-device state, including a live InstantDB
 * **refresh token**. This one-shot cleanup deletes all of it, so no unused
 * credential lingers on the device. It is idempotent (a no-op once clean),
 * cheap, and runs on every cold start off the main thread.
 *
 * What the removed `:core-sync` persisted, and where:
 *  - `storyvox.secrets` (EncryptedSharedPreferences, the unqualified
 *    `SharedPreferences` binding): every `instantdb.*` key (the session's
 *    refresh token, user id and email, and the per-domain sync stamps), plus
 *    `sync.passphrase` (secrets-sync passphrase) and `sync.inbox.seen.v1`
 *    (push-to-Candela inbox cursor).
 *  - The `storyvox_settings` DataStore: legacy `instantdb.*` stamp keys (the
 *    live ones were renamed `local_stamp.*`) and the sync-onboarding flag.
 *  - A separate `storyvox_sync_tombstones` DataStore file.
 *
 * This file is the one deliberate exception in `NoCloudSyncGuardTest`: it
 * has to name the legacy keys in order to delete them.
 */
object LegacySyncKeys {
    const val PREFIX = "instantdb."
    val SECRETS_EXTRA: Set<String> = setOf("sync.passphrase", "sync.inbox.seen.v1")
    val SETTINGS_EXTRA: Set<String> = setOf("pref_sync_onboarding_dismissed")
    const val TOMBSTONES_FILE = "datastore/storyvox_sync_tombstones.preferences_pb"

    fun isLegacySecret(key: String): Boolean = key.startsWith(PREFIX) || key in SECRETS_EXTRA
    fun isLegacySetting(key: String): Boolean = key.startsWith(PREFIX) || key in SETTINGS_EXTRA
}

@Singleton
class LegacySyncCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SharedPreferences,
) {
    /** Returns how many legacy entries were removed (0 once clean). */
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        cleanSecrets(secrets) + cleanSettings(context.settingsDataStore) + deleteTombstones(context.filesDir)
    }

    companion object {
        // RED-FIRST STUB (#1821): real deletion lands in the next commit.
        fun cleanSecrets(prefs: SharedPreferences): Int = 0

        suspend fun cleanSettings(store: DataStore<Preferences>): Int = 0

        fun deleteTombstones(filesDir: File): Int = 0
    }
}
