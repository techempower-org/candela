package `in`.jphe.storyvox.playback.voice

import `in`.jphe.storyvox.playback.voice.engines.AzureEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.KittenEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.KokoroEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.PiperEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.SupertonicEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.SystemTtsEnginePlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1372 — [VoiceEngineRegistry] shape + dispatch tests.
 *
 * Builds the registry directly from the six in-tree plugins (each has a
 * no-arg `@Inject` constructor) keyed by `engineId`, exactly as the Hilt
 * `@IntoMap` multibinding does at runtime — so this exercises the real
 * routing without standing up Hilt.
 *
 * Deliberately native-free: it never reads `sampleRate` or calls
 * `generateAudioPCM` (those reach the VoxSherpa JNI engines). The seam's
 * value here is metadata + dispatch — which [EngineType] routes to which
 * plugin, export capability, and that the union of catalog entries
 * reproduces [VoiceCatalog.voices].
 */
class VoiceEngineRegistryTest {

    private fun realPlugins(): List<VoiceEnginePlugin> = listOf(
        PiperEnginePlugin(),
        KokoroEnginePlugin(),
        KittenEnginePlugin(),
        SupertonicEnginePlugin(),
        AzureEnginePlugin(),
        SystemTtsEnginePlugin(),
    )

    /** The map exactly as `VoiceEnginePluginModule`'s @StringKey binds it. */
    private fun registry(): VoiceEngineRegistry =
        VoiceEngineRegistry(realPlugins().associateBy { it.engineId })

    @Test fun `registry holds all six engine plugins`() {
        assertEquals(6, registry().all().size)
    }

    @Test fun `byId resolves each family key to its plugin`() {
        val r = registry()
        assertEquals(VoiceFamilyIds.PIPER, r.byId(VoiceFamilyIds.PIPER)?.engineId)
        assertEquals(VoiceFamilyIds.KOKORO, r.byId(VoiceFamilyIds.KOKORO)?.engineId)
        assertEquals(VoiceFamilyIds.KITTEN, r.byId(VoiceFamilyIds.KITTEN)?.engineId)
        assertEquals(VoiceFamilyIds.SUPERTONIC, r.byId(VoiceFamilyIds.SUPERTONIC)?.engineId)
        assertEquals(VoiceFamilyIds.AZURE, r.byId(VoiceFamilyIds.AZURE)?.engineId)
        assertEquals(VoiceFamilyIds.SYSTEM_TTS, r.byId(VoiceFamilyIds.SYSTEM_TTS)?.engineId)
    }

    @Test fun `byId returns null for an unregistered id`() {
        assertNull(registry().byId("voice_does_not_exist"))
    }

    @Test fun `forType dispatches every EngineType variant to the owning plugin`() {
        val r = registry()
        assertEquals(VoiceFamilyIds.PIPER, r.forType(EngineType.Piper)?.engineId)
        assertEquals(VoiceFamilyIds.KOKORO, r.forType(EngineType.Kokoro(speakerId = 7))?.engineId)
        assertEquals(VoiceFamilyIds.KITTEN, r.forType(EngineType.Kitten(speakerId = 3))?.engineId)
        assertEquals(VoiceFamilyIds.SUPERTONIC, r.forType(EngineType.Supertonic(speakerId = 2))?.engineId)
        assertEquals(
            VoiceFamilyIds.AZURE,
            r.forType(EngineType.Azure(voiceName = "en-US-AvaDragonHDLatestNeural", region = "eastus"))?.engineId,
        )
        assertEquals(
            VoiceFamilyIds.SYSTEM_TTS,
            r.forType(EngineType.SystemTts(engineName = "com.google.android.tts", voiceName = "en-us-x-iol-network"))?.engineId,
        )
    }

    @Test fun `forType agrees with the existing voiceFamilyId mapping for every variant`() {
        // Ties the new dispatch seam to the pre-#1372 EngineType→family
        // map: the plugin that handles a type must be the one keyed under
        // that type's family id. If they ever drift, both this and
        // VoiceFamilyRegistryTest's mapping tests should be revisited.
        val r = registry()
        val variants = listOf(
            EngineType.Piper,
            EngineType.Kokoro(speakerId = 0),
            EngineType.Kitten(speakerId = 0),
            EngineType.Supertonic(speakerId = 0),
            EngineType.Azure(voiceName = "v", region = "eastus"),
            EngineType.SystemTts(engineName = "e", voiceName = "v"),
        )
        variants.forEach { type ->
            assertEquals(
                "forType($type) should resolve the plugin keyed under ${type.voiceFamilyId()}",
                type.voiceFamilyId(),
                r.forType(type)?.engineId,
            )
        }
    }

    @Test fun `only the in-process model engines support export`() {
        val r = registry()
        assertTrue(r.byId(VoiceFamilyIds.PIPER)!!.supportsExport)
        assertTrue(r.byId(VoiceFamilyIds.KOKORO)!!.supportsExport)
        assertTrue(r.byId(VoiceFamilyIds.KITTEN)!!.supportsExport)
        assertTrue(r.byId(VoiceFamilyIds.SUPERTONIC)!!.supportsExport)
        // Cloud / framework engines are offline-export-incapable.
        assertFalse(r.byId(VoiceFamilyIds.AZURE)!!.supportsExport)
        assertFalse(r.byId(VoiceFamilyIds.SYSTEM_TTS)!!.supportsExport)
    }

    @Test fun `allCatalogEntries reproduces the static VoiceCatalog voices`() {
        // The seam's catalog union must equal the legacy static roster:
        // the in-process engines contribute their entries; Azure / System
        // TTS contribute nothing static (they're roster-driven at runtime).
        // Compared as sets — registry order is unspecified (Hilt map order).
        assertEquals(VoiceCatalog.voices.toSet(), registry().allCatalogEntries().toSet())
        assertEquals(
            "no duplicate catalog entries across plugins",
            VoiceCatalog.voices.size,
            registry().allCatalogEntries().size,
        )
    }

    @Test fun `allFamilyDescriptors covers the six engine families but not the placeholder`() {
        val ids = registry().allFamilyDescriptors().map { it.id }.toSet()
        assertEquals(
            setOf(
                VoiceFamilyIds.PIPER,
                VoiceFamilyIds.KOKORO,
                VoiceFamilyIds.KITTEN,
                VoiceFamilyIds.SUPERTONIC,
                VoiceFamilyIds.AZURE,
                VoiceFamilyIds.SYSTEM_TTS,
            ),
            ids,
        )
        // The "VoxSherpa upstreams" placeholder is not an engine — it
        // stays owned by VoiceFamilyRegistry, never surfaced here.
        assertFalse(VoiceFamilyIds.VOXSHERPA_UPSTREAMS in ids)
    }

    @Test fun `each engine plugin reports the family descriptor matching its engineId`() {
        registry().all().forEach { plugin ->
            assertEquals(plugin.engineId, plugin.familyDescriptor().id)
        }
    }

    @Test fun `plugin family descriptors are the shared VoiceFamilyDescriptors constants`() {
        // Guards against a plugin minting a divergent descriptor: it must
        // hand back the same object VoiceFamilyRegistry renders.
        val r = registry()
        assertSame(VoiceFamilyDescriptors.PIPER, r.byId(VoiceFamilyIds.PIPER)!!.familyDescriptor())
        assertSame(VoiceFamilyDescriptors.AZURE, r.byId(VoiceFamilyIds.AZURE)!!.familyDescriptor())
        assertSame(VoiceFamilyDescriptors.SYSTEM_TTS, r.byId(VoiceFamilyIds.SYSTEM_TTS)!!.familyDescriptor())
    }

    @Test fun `construction fails fast when a plugin is bound under the wrong key`() {
        // Mirrors the @StringKey contract: the map key must equal the
        // plugin's engineId. A mismatch is a wiring bug and should blow
        // up at graph build, not silently break byId().
        val ex = assertThrows(IllegalStateException::class.java) {
            VoiceEngineRegistry(mapOf(VoiceFamilyIds.KOKORO to PiperEnginePlugin()))
        }
        assertTrue(
            "message should name the offending binding",
            ex.message?.contains("engineId") == true,
        )
    }
}
