package `in`.jphe.storyvox.testkit.voice

import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.ModelSpec
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.playback.voice.toEngineTypeOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract every [VoiceEnginePlugin] must satisfy (epic/plugin-dx B3).
 * Subclass in `core-playback`'s unit tests (or your engine's, if it ever
 * moves out-of-module), implement the two hooks, and the metadata +
 * coherence rules become executable checks.
 *
 * Scope is deliberately METADATA + COHERENCE: native synthesis
 * (VoxSherpa JNI) cannot run on the JVM, so this kit never calls
 * `generateAudioPCM` for engines that claim export support — the only
 * synth call it makes is the documented-null probe on
 * `supportsExport = false` plugins (cloud/framework engines, which
 * return null without touching native code). Model loading
 * ([VoiceEnginePlugin.loadModel]) and pool acquisition
 * (`StreamingSynth.acquirePool`) are likewise out of JVM scope —
 * CI's `:app` build proves the Hilt binding and on-device QA proves
 * audio.
 *
 * ## Why the `supportsExport = false` null contract matters
 *
 * The flag is load-bearing beyond the export picker: a
 * `supportsExport = false` engine that reaches the background
 * pre-render worker WITHOUT an upstream pre-filter hits
 * `ChapterRenderJob`'s capability gate and reads as a LOAD FAILURE →
 * `Result.retry()` with backoff (not permanent-fail, not an
 * empty-but-complete cache entry). Today's cloud/framework engines are
 * pre-filtered upstream so the gate is defensive; a new engine relying
 * on it inherits retry semantics — set the flag honestly and give your
 * engine an upstream filter if retry-backoff isn't what you want.
 *
 * See docs/CONTRIBUTING-VOICES.md for the walkthrough this kit anchors.
 */
abstract class VoiceEnginePluginContractTest {

    /** Construct YOUR plugin. Local-model plugins take `dagger.Lazy`
     *  deps that this kit never touches — pass inert fakes
     *  (`dagger.Lazy { error("unused") }`). */
    protected abstract fun plugin(): VoiceEnginePlugin

    /** EngineKeys this plugin claims (one per catalog family voice-shape,
     *  e.g. a couple of speaker ids for shared-model engines). */
    protected abstract fun sampleKeys(): List<EngineKey>

    @Test fun `engineId is a known family id`() =
        assertTrue(
            "engineId must be a voice_* VoiceFamilyIds constant, got ${plugin().engineId}",
            plugin().engineId.startsWith("voice_"),
        )

    @Test fun `sample keys belong to this plugin's family`() {
        for (k in sampleKeys()) {
            assertEquals(
                "sampleKey $k names a different family than the plugin",
                plugin().engineId,
                k.engineId,
            )
        }
    }

    @Test fun `catalog entries are unique and non-empty where declared`() {
        val ids = plugin().catalogEntries().map { it.id }
        assertEquals("duplicate catalog entry ids: $ids", ids.size, ids.toSet().size)
        for (id in ids) assertTrue("blank catalog entry id", id.isNotBlank())
    }

    @Test fun `handles() agrees with sampleKeys`() {
        for (k in sampleKeys()) {
            // Keys of not-yet-de-sealed families round-trip to an EngineType;
            // a key that doesn't (future de-sealed-only engines) skips this
            // check — byKey() is their dispatch path, not handles().
            val t = k.toEngineTypeOrNull() ?: continue
            assertTrue("plugin must handle its own key $k", plugin().handles(t))
        }
    }

    @Test fun `family descriptor id matches engineId`() =
        assertEquals(plugin().engineId, plugin().familyDescriptor().id)

    @Test fun `export claim is coherent`() {
        // supportsExport=false plugins must return null from generateAudioPCM
        // per the contract kdoc (cloud/framework engines — safe on the JVM,
        // they never reach native code). Export-capable engines are NOT
        // synth-probed here: their synth is JNI and belongs to on-device QA.
        if (!plugin().supportsExport) {
            val t = sampleKeys().firstNotNullOfOrNull { it.toEngineTypeOrNull() } ?: return
            assertEquals(
                "supportsExport=false plugins must return null from generateAudioPCM",
                null,
                plugin().generateAudioPCM(t, "contract probe"),
            )
        }
    }

    @Test fun `export-capable engines declare a local model`() {
        // The true side of the export claim, JVM-assertable without JNI:
        // offline audiobook export renders on-device, so supportsExport=true
        // requires a local ModelSpec. This catches the scaffold-stub trap —
        // flipping supportsExport to true while modelSpec()/generateAudioPCM
        // are still the stub values would otherwise pass the whole kit and
        // export silent, empty audiobooks.
        if (plugin().supportsExport) {
            val t = sampleKeys().firstNotNullOfOrNull { it.toEngineTypeOrNull() } ?: return
            val spec = plugin().modelSpec(t, sampleKeys().first().engineId)
            assertTrue(
                "supportsExport=true requires a local ModelSpec, got ModelSpec.None",
                spec != ModelSpec.None,
            )
        }
    }
}
