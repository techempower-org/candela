package `in`.jphe.storyvox.playback.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Real-DataStore tests for [VoiceLibraryCollapse] (#130). Same shape
 * as [VoiceFavoritesTest] — temp-file preferences DataStore against
 * a [TemporaryFolder] so the persistence layer is exactly what
 * production runs against; no Robolectric needed.
 *
 * The semantic interesting bit lives in [VoiceLibraryCollapse.isCollapsed]
 * (default policy: Installed expanded, Available collapsed) and the
 * way the on-disk set stores **flipped-from-default** keys only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLibraryCollapseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var collapse: VoiceLibraryCollapse

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "voice_library_collapsed_engines_v1.preferences_pb")
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        collapse = VoiceLibraryCollapse.forTesting(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `initial flipped set is empty`() = runTest {
        assertEquals(emptySet<String>(), collapse.flippedKeys.first())
    }

    @Test
    fun `installed default is expanded — empty set means not collapsed`() {
        val key = EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Piper)
        assertFalse(VoiceLibraryCollapse.isCollapsed(key, flipped = emptySet()))
    }

    @Test
    fun `available default is collapsed — empty set means collapsed`() {
        val key = EngineCollapseKey(VoiceLibrarySection.Available, VoiceEngineId.Piper)
        assertTrue(VoiceLibraryCollapse.isCollapsed(key, flipped = emptySet()))
    }

    @Test
    fun `flipping installed engine collapses it`() = runTest {
        val key = EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Kokoro)
        collapse.toggle(key)

        val flipped = collapse.flippedKeys.first()
        assertEquals(setOf("installed:Kokoro"), flipped)
        assertTrue(VoiceLibraryCollapse.isCollapsed(key, flipped))
    }

    @Test
    fun `flipping available engine expands it`() = runTest {
        val key = EngineCollapseKey(VoiceLibrarySection.Available, VoiceEngineId.Kokoro)
        collapse.toggle(key)

        val flipped = collapse.flippedKeys.first()
        assertEquals(setOf("available:Kokoro"), flipped)
        assertFalse(VoiceLibraryCollapse.isCollapsed(key, flipped))
    }

    @Test
    fun `toggle is idempotent — flip then unflip returns to default`() = runTest {
        val key = EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Piper)
        collapse.toggle(key)
        collapse.toggle(key)

        val flipped = collapse.flippedKeys.first()
        assertEquals(emptySet<String>(), flipped)
        // Default is expanded — back where we started.
        assertFalse(VoiceLibraryCollapse.isCollapsed(key, flipped))
    }

    @Test
    fun `flipping one engine doesn't affect the other in same section`() = runTest {
        val piperKey = EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Piper)
        val kokoroKey = EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Kokoro)

        collapse.toggle(piperKey)

        val flipped = collapse.flippedKeys.first()
        assertTrue(VoiceLibraryCollapse.isCollapsed(piperKey, flipped))
        // Kokoro under Installed still defaults to expanded.
        assertFalse(VoiceLibraryCollapse.isCollapsed(kokoroKey, flipped))
    }

    @Test
    fun `flipping installed Piper doesn't affect available Piper`() = runTest {
        val installedKey = EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Piper)
        val availableKey = EngineCollapseKey(VoiceLibrarySection.Available, VoiceEngineId.Piper)

        collapse.toggle(installedKey)

        val flipped = collapse.flippedKeys.first()
        assertTrue(VoiceLibraryCollapse.isCollapsed(installedKey, flipped))
        // Available default is still collapsed; not affected by the
        // Installed flip even though the engine matches.
        assertTrue(VoiceLibraryCollapse.isCollapsed(availableKey, flipped))
    }

    @Test
    fun `round-trip survives reinitialization of the collapse facade`() = runTest {
        val key = EngineCollapseKey(VoiceLibrarySection.Available, VoiceEngineId.Kokoro)
        collapse.toggle(key)
        assertEquals(setOf("available:Kokoro"), collapse.flippedKeys.first())

        // Bind a fresh facade against the same underlying store —
        // simulates app process restart against the persisted prefs.
        val rebound = VoiceLibraryCollapse.forTesting(store)
        assertEquals(setOf("available:Kokoro"), rebound.flippedKeys.first())
    }

    @Test
    fun `storeKey format is section colon engine`() {
        // The exact string is part of the on-disk schema (see _v1
        // suffix on the DataStore name) — a future refactor that
        // accidentally changes case or separator would silently
        // ignore existing prefs. Pin the format here.
        assertEquals(
            "installed:Piper",
            EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Piper).storeKey(),
        )
        assertEquals(
            "available:Kokoro",
            EngineCollapseKey(VoiceLibrarySection.Available, VoiceEngineId.Kokoro).storeKey(),
        )
    }
}
