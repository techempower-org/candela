package `in`.jphe.storyvox.playback.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #1500 / #1501 — a de-sealed engine (an `@VoicePlugin` with no built-in
 * [EngineType] variant) is representable in the catalog types, resolves
 * through the registry by key, and surfaces a family card + presentation,
 * all with zero edits to any sealed `when`.
 */
class DeSealedEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakePlugin(
        override val engineId: String = FAKE_ID,
        private val ready: Boolean = true,
    ) : VoiceEnginePlugin {
        override val sampleRate: Int = 16_000
        override val supportsExport: Boolean = false
        override fun handles(type: EngineType): Boolean = false
        override fun generateAudioPCM(type: EngineType, text: String, speed: Float, pitch: Float): ByteArray? = null
        override fun isVoiceReady(type: EngineType, voiceId: String): Boolean = ready
        override fun catalogEntries(): List<CatalogEntry> = listOf(
            CatalogEntry(
                id = "fake_alice",
                displayName = "Alice",
                language = "en_US",
                sizeBytes = 0L,
                qualityLevel = QualityLevel.High,
                engineKey = EngineKey(engineId, speakerId = 2),
                piper = null,
            ),
        )
        override fun familyDescriptor(): VoiceFamilyDescriptor = VoiceFamilyDescriptor(
            id = engineId,
            displayName = "Fake TTS",
            description = "test",
            sourceUrl = "https://example.com",
            license = "MIT",
            sizeHint = "0 MB",
        )
    }

    @Test fun `unknown engine id maps to Plugin and round-trips`() {
        val key = EngineKey(FAKE_ID, speakerId = 4, params = mapOf("style" to "calm"))
        val type = key.toEngineType()
        assertEquals(EngineType.Plugin(key), type)
        assertEquals(key, type.toEngineKey())
        // The strict built-in view still says "not a built-in".
        assertEquals(null, key.toEngineTypeOrNull())
    }

    @Test fun `built-in keys never become Plugin`() {
        assertEquals(EngineType.Kokoro(3), EngineKey(VoiceFamilyIds.KOKORO, 3).toEngineType())
        assertThrows(IllegalArgumentException::class.java) {
            EngineType.Plugin(EngineKey(VoiceFamilyIds.PIPER))
        }
        // A malformed built-in key is a construction bug, not a plugin.
        assertThrows(IllegalArgumentException::class.java) {
            EngineKey(VoiceFamilyIds.KOKORO).toEngineType()
        }
    }

    @Test fun `catalog rows carry the key and expose the typed view`() {
        val entry = FakePlugin().catalogEntries().single()
        assertEquals(FAKE_ID, entry.engineKey.engineId)
        assertTrue(entry.engineType is EngineType.Plugin)
        assertEquals(FAKE_ID, entry.engineType.voiceFamilyId())

        val viaType = UiVoiceInfo(
            id = "p", displayName = "P", language = "en_US", sizeBytes = 0L,
            isInstalled = true, qualityLevel = QualityLevel.Low, engineType = EngineType.Kitten(1),
        )
        val viaKey = viaType.copy(engineKey = EngineKey(VoiceFamilyIds.KITTEN, 1))
        assertEquals(viaType, viaKey)
        assertEquals(EngineType.Kitten(1), viaKey.engineType)
    }

    @Test fun `registry dispatches a plugin type by key without handles`() {
        val plugin = FakePlugin()
        val registry = VoiceEngineRegistry(mapOf(FAKE_ID to plugin))
        val type = EngineKey(FAKE_ID, 2).toEngineType()
        assertSame(plugin, registry.forType(type))
        assertSame(plugin, registry.byKey(EngineKey(FAKE_ID)))
        assertEquals(null, registry.forType(EngineKey("voice_absent").toEngineType()))
    }

    @Test fun `family registry appends plugin cards ahead of the placeholder`() {
        val registry = VoiceEngineRegistry(mapOf(FAKE_ID to FakePlugin()))
        val families = VoiceFamilyRegistry(dagger.Lazy { registry })
        val ids = families.descriptors.map { it.id }
        assertEquals(FAKE_ID, ids[ids.size - 2])
        assertEquals(VoiceFamilyIds.VOXSHERPA_UPSTREAMS, ids.last())
        assertTrue(FAKE_ID in families.toggleableIds)
        // Built-ins are unchanged and not duplicated.
        assertEquals(VoiceFamilyRegistry().descriptors.size + 1, ids.size)
    }

    @Test fun `plugin presentation defaults sort local plugins before cloud`() {
        val families = VoiceFamilyRegistry(dagger.Lazy { VoiceEngineRegistry(mapOf(FAKE_ID to FakePlugin())) })
        val p = families.presentations.forId(FAKE_ID)
        assertEquals("Fake TTS", p.shortLabel)
        assertEquals("Fake TTS", p.sectionLabel)
        assertEquals("fake tts", p.searchTerm)
        assertEquals(FAKE_ID, p.collapseToken)
        assertTrue(p.displayOrder > families.presentations.forId(VoiceFamilyIds.SUPERTONIC).displayOrder)
        assertTrue(p.displayOrder < families.presentations.forId(VoiceFamilyIds.AZURE).displayOrder)
        // Unknown ids still render (neutral defaults), never crash.
        assertEquals("voice_ghost", VoicePresentations.BUILT_IN.forId("voice_ghost").collapseToken)
    }

    @Test fun `built-in presentations keep their historical labels`() {
        val p = VoicePresentations.BUILT_IN
        assertEquals("Kitten (Lite)", p.forId(VoiceFamilyIds.KITTEN).sectionLabel)
        assertEquals("Kitten", p.forId(VoiceFamilyIds.KITTEN).shortLabel)
        assertEquals("Azure (Cloud)", p.forId(VoiceFamilyIds.AZURE).sectionLabel)
        assertEquals("Supertonic 3", p.forId(VoiceFamilyIds.SUPERTONIC).sectionLabel)
        assertEquals("system tts", p.forId(VoiceFamilyIds.SYSTEM_TTS).searchTerm)
        assertEquals(
            listOf(QualityLevel.Low, QualityLevel.Medium, QualityLevel.High),
            p.forId(VoiceFamilyIds.PIPER).tierOrder,
        )
    }

    @Test fun `default readiness follows the model spec files`() {
        val onnx = tmp.newFile("model.onnx")
        val tokens = java.io.File(tmp.root, "tokens.txt")
        val spec = ModelSpec.OnnxWithTokens(onnx, tokens)
        assertFalse(spec.isPresentOnDisk())
        tokens.writeText("a")
        assertTrue(spec.isPresentOnDisk())
        assertTrue(ModelSpec.None.isPresentOnDisk())
        val emptyDir = tmp.newFolder("shared")
        assertFalse(ModelSpec.SharedDir(emptyDir).isPresentOnDisk())
        java.io.File(emptyDir, "x.onnx").writeText("x")
        assertTrue(ModelSpec.SharedDir(emptyDir).isPresentOnDisk())
    }

    @Test fun `background render defaults to the export flag and can diverge`() {
        assertFalse(FakePlugin().supportsBackgroundRender)
        val liveOnly = object : VoiceEnginePlugin by FakePlugin() {
            override val supportsExport: Boolean = true
            override val supportsBackgroundRender: Boolean = false
        }
        assertTrue(liveOnly.supportsExport)
        assertFalse(liveOnly.supportsBackgroundRender)
    }

    private companion object {
        const val FAKE_ID = "voice_fake"
    }
}
