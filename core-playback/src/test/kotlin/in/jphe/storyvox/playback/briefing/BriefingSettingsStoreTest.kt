package `in`.jphe.storyvox.playback.briefing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import `in`.jphe.storyvox.data.briefing.BriefingItem
import `in`.jphe.storyvox.data.briefing.BriefingSettings
import `in`.jphe.storyvox.data.briefing.BriefingSources
import `in`.jphe.storyvox.data.briefing.PrebuiltBriefing
import `in`.jphe.storyvox.data.source.SourceIds
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #1467 slice B — real-DataStore round-trips for [BriefingSettingsStore]. */
@OptIn(ExperimentalCoroutinesApi::class)
class BriefingSettingsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var data: DataStore<Preferences>
    private lateinit var store: BriefingSettingsStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "morning_briefing.preferences_pb")
        data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = BriefingSettingsStore(data)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test fun `fresh install yields normalized defaults and no prebuilt queue`() = runTest {
        val s = store.current()
        assertEquals(BriefingSettings(), s)
        assertEquals(BriefingSources.CATALOG.map { it.sourceId }, s.config.sources.map { it.sourceId })
        assertNull(store.currentPrebuilt())
    }

    @Test fun `picker and schedule changes persist`() = runTest {
        store.update { s ->
            s.copy(
                config = s.config.copy(
                    sources = s.config.sources.map {
                        if (it.sourceId == SourceIds.GOOGLE_NEWS) it.copy(enabled = true, count = 5) else it
                    },
                ),
                schedule = s.schedule.copy(enabled = true, hour = 7, minute = 15),
            )
        }
        val reread = BriefingSettingsStore(data).current()
        val gn = reread.config.sources.first { it.sourceId == SourceIds.GOOGLE_NEWS }
        assertTrue(gn.enabled)
        assertEquals(5, gn.count)
        assertEquals(7, reread.schedule.hour)
        assertEquals(15, reread.schedule.minute)
        assertTrue(reread.schedule.enabled)
    }

    @Test fun `prebuilt queue round-trips`() = runTest {
        val prebuilt = PrebuiltBriefing(
            epochDay = 20_000,
            configKey = "k",
            builtAtMillis = 42,
            items = listOf(BriefingItem("rss:1", "rss:1:c", "rss", "Title")),
        )
        store.savePrebuilt(prebuilt)
        assertEquals(prebuilt, store.currentPrebuilt())
    }

    @Test fun `a corrupt settings blob falls back to defaults`() = runTest {
        data.edit { it[stringPreferencesKey("briefing_settings_json")] = "{not json" }
        assertEquals(BriefingSettings(), store.current())
    }
}
