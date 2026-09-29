package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Issue #1821 — old installs must not keep the removed sync's credential or state. */
class LegacySyncCleanupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "storyvox_settings.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `removes the InstantDB session, passphrase and sync state from secrets, keeps other secrets`() {
        val secrets = FakeSecrets()
        secrets.edit()
            .putString("instantdb.refresh_token", "rt-secret")
            .putString("instantdb.user_id", "u-1")
            .putString("instantdb.email", "a@b.c")
            .putLong("instantdb.bookmarks_synced_at", 5L)
            .putString("sync.passphrase", "hunter2")
            .putString("sync.inbox.seen.v1", "{}")
            .putString("notion.api_token", "keep-me")
            .putString("pref_source_discord_token", "keep-me-too")
            .apply()

        val removed = LegacySyncCleanup.cleanSecrets(secrets)

        assertEquals(6, removed)
        assertFalse("refresh token must be gone", secrets.contains("instantdb.refresh_token"))
        assertFalse(secrets.contains("instantdb.email"))
        assertFalse(secrets.contains("sync.passphrase"))
        assertTrue("unrelated secrets survive", secrets.contains("notion.api_token"))
        assertTrue(secrets.contains("pref_source_discord_token"))
        assertEquals("second run is a no-op", 0, LegacySyncCleanup.cleanSecrets(secrets))
    }

    @Test
    fun `prunes legacy settings keys only`() = runTest {
        store.edit {
            it[longPreferencesKey("instantdb.settings_synced_at")] = 1L
            it[stringPreferencesKey("instantdb.settings_field_stamps_v1")] = "{}"
            it[booleanPreferencesKey("pref_sync_onboarding_dismissed")] = true
            it[longPreferencesKey("local_stamp.settings_synced_at")] = 2L
            it[stringPreferencesKey("pref_theme_override")] = "dark"
        }

        val removed = LegacySyncCleanup.cleanSettings(store)

        val keys = store.data.first().asMap().keys.map { it.name }.toSet()
        assertEquals(3, removed)
        assertEquals(setOf("local_stamp.settings_synced_at", "pref_theme_override"), keys)
        assertEquals(0, LegacySyncCleanup.cleanSettings(store))
    }

    @Test
    fun `deletes the tombstones DataStore file`() {
        val filesDir = tempFolder.newFolder("files")
        val tomb = File(filesDir, LegacySyncKeys.TOMBSTONES_FILE).apply { parentFile.mkdirs(); writeText("x") }

        assertEquals(1, LegacySyncCleanup.deleteTombstones(filesDir))
        assertFalse(tomb.exists())
        assertEquals(0, LegacySyncCleanup.deleteTombstones(filesDir))
    }
}
