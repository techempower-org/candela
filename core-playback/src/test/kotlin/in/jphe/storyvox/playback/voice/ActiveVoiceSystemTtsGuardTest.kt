package `in`.jphe.storyvox.playback.voice

import android.app.Application
import `in`.jphe.storyvox.data.source.AzureVoiceDescriptor
import `in`.jphe.storyvox.data.source.AzureVoiceProvider
import `in`.jphe.storyvox.data.source.SystemTtsVoiceDescriptor
import `in`.jphe.storyvox.data.source.SystemTtsVoiceProvider
import `in`.jphe.storyvox.playback.voice.engines.AzureEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.KittenEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.KokoroEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.PiperEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.SupertonicEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.SystemTtsEnginePlugin
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Issue #1383 / #1384 — guard non-system voices from System TTS
 * reconnection churn.
 *
 * On Samsung devices the OS TTS enters a connect/disconnect loop
 * (private-engine fallback), which churns [SystemTtsVoiceProvider]'s
 * roster repeatedly. [VoiceManager.activeVoice] is a `combine` over that
 * roster, so before the fix it re-emitted on every churn — even when the
 * active voice is a non-system engine (Piper) whose resolved descriptor
 * is roster-independent. Those no-op re-emissions reached
 * `EnginePlayer.observeActiveVoice` and could tear down + rebuild the
 * pipeline, cancelling and restarting Piper synthesis in a tight loop.
 *
 * The fix terminates `activeVoice` with `distinctUntilChanged()`. This
 * test pins the behaviour against the real [VoiceManager.activeVoice]:
 * with an active Piper voice, three connect/disconnect cycles of the
 * System TTS roster produce exactly one active-voice emission — so the
 * pipeline never sees a (spurious) voice change to rebuild on.
 *
 * Robolectric for the same reason as [VoiceManagerTest]: VoiceManager
 * needs a real `Context.filesDir` + DataStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActiveVoiceSystemTtsGuardTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Each Robolectric test shares the JVM; wipe any voices/ residue so
        // install-state checks start deterministic.
        java.io.File(context.filesDir, "voices").deleteRecursively()
    }

    @Test
    fun activeVoice_ignoresSystemTtsReconnectionChurn_whenPiperVoiceActive() = runBlocking {
        // Controllable System TTS roster — we drive the Samsung-style
        // connect/disconnect loop by flipping its value.
        val systemTtsRoster = MutableStateFlow<List<SystemTtsVoiceDescriptor>>(emptyList())
        val churningSystemTts = object : SystemTtsVoiceProvider {
            override val voices: Flow<List<SystemTtsVoiceDescriptor>> = systemTtsRoster
            override suspend fun refresh() = Unit
        }
        val vm = VoiceManager(context, EmptyAzureProvider, churningSystemTts)

        // Active voice is a Piper (non-system) voice.
        vm.setActive(PIPER_ID)

        // Sanity: the active voice resolves and the VoiceEngineRegistry
        // classifies it as a NON-system engine — the precondition the guard
        // relies on (a system roster change cannot change a Piper voice).
        val active = vm.activeVoice.first()
        assertEquals(PIPER_ID, active?.id)
        val plugin = registry().forType(active!!.engineType)
        assertNotEquals(
            "active Piper voice must NOT be classified as a System TTS engine",
            VoiceFamilyIds.SYSTEM_TTS,
            plugin?.engineId,
        )
        assertEquals(VoiceFamilyIds.PIPER, plugin?.engineId)

        // Record every active-voice emission while we churn the roster.
        // CopyOnWriteArrayList so waitUntil's iteration can't race the
        // collector's adds (no ConcurrentModificationException).
        val emissions = CopyOnWriteArrayList<UiVoiceInfo?>()
        val collectorScope = CoroutineScope(Dispatchers.IO + Job())
        val job = collectorScope.launch { vm.activeVoice.collect { emissions.add(it) } }

        // Wait for the initial Piper emission to land.
        waitUntil { emissions.any { it?.id == PIPER_ID } }

        // Three Samsung-style connect/disconnect cycles. Each flip re-fires
        // the activeVoice combine; the spacing is wide enough that the
        // collector observes each change (no StateFlow conflation), so
        // WITHOUT the guard this would record ~6 extra Piper emissions.
        val systemVoice = sampleSystemVoice()
        repeat(3) {
            systemTtsRoster.value = listOf(systemVoice) // engine connects
            Thread.sleep(STEP_MS)
            systemTtsRoster.value = emptyList() // engine drops
            Thread.sleep(STEP_MS)
        }
        Thread.sleep(SETTLE_MS)
        job.cancelAndJoin()

        val piperEmissions = emissions.count { it?.id == PIPER_ID }
        assertEquals(
            "Active Piper voice must emit exactly once despite System TTS " +
                "reconnection churn (distinctUntilChanged guard) — got $emissions",
            1,
            piperEmissions,
        )
        // And the System TTS roster churn never leaked a System TTS voice
        // into the active-voice stream.
        assertTrue(
            "no active-voice emission may resolve to a System TTS engine — got $emissions",
            emissions.none { it != null && it.engineType is EngineType.SystemTts },
        )
    }

    // ----- helpers -----

    /** Poll until [cond] holds or [timeoutMs] elapses. */
    private fun waitUntil(timeoutMs: Long = 3_000L, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond() && System.nanoTime() < deadline) Thread.sleep(20)
    }

    /** The six in-tree engine plugins, keyed by engineId — exactly as
     *  `VoiceEnginePluginModule` binds them at runtime. */
    private fun registry(): VoiceEngineRegistry = VoiceEngineRegistry(
        listOf(
            PiperEnginePlugin(),
            KokoroEnginePlugin(),
            KittenEnginePlugin(),
            SupertonicEnginePlugin(),
            AzureEnginePlugin(),
            SystemTtsEnginePlugin(),
        ).associateBy { it.engineId },
    )

    private fun sampleSystemVoice(): SystemTtsVoiceDescriptor = SystemTtsVoiceDescriptor(
        engineName = "com.samsung.SMT",
        engineLabel = "Samsung text-to-speech engine",
        voiceName = "en-US-language",
        displayName = "American English (offline) #1",
        locale = "en-US",
        isNetworkConnectionRequired = false,
    )

    private object EmptyAzureProvider : AzureVoiceProvider {
        override val voices: Flow<List<AzureVoiceDescriptor>> = flowOf(emptyList())
        override suspend fun refresh() = Unit
    }

    private companion object {
        const val PIPER_ID = "piper_lessac_en_US_high"
        const val STEP_MS = 120L
        const val SETTLE_MS = 300L
    }
}
