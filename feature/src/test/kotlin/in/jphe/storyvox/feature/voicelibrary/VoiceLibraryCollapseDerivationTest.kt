package `in`.jphe.storyvox.feature.voicelibrary

import `in`.jphe.storyvox.playback.voice.EngineCollapseKey
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import `in`.jphe.storyvox.playback.voice.VoiceLibrarySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [computeCollapsedEngines] — the pure helper that
 * projects the persisted `flipped` set onto the engines actually
 * present in each section, applying the per-section default policy
 * along the way (Installed expanded; Available collapsed). The screen
 * reads `state.collapsedEngines` directly, so this is the contract
 * its rendering checks.
 */
class VoiceLibraryCollapseDerivationTest {

    @Test
    fun `default state collapses every available engine and no installed engines`() {
        val collapsed = computeCollapsedEngines(
            installedEngines = setOf(VoiceFamilyIds.PIPER, VoiceFamilyIds.KOKORO),
            availableEngines = setOf(VoiceFamilyIds.PIPER, VoiceFamilyIds.KOKORO),
            flipped = emptySet(),
        )

        // Available defaults to collapsed → both available engines in.
        assertTrue(EngineCollapseKey(VoiceLibrarySection.Available, "Piper") in collapsed)
        assertTrue(EngineCollapseKey(VoiceLibrarySection.Available, "Kokoro") in collapsed)
        // Installed defaults to expanded → neither installed engine in.
        assertFalse(EngineCollapseKey(VoiceLibrarySection.Installed, "Piper") in collapsed)
        assertFalse(EngineCollapseKey(VoiceLibrarySection.Installed, "Kokoro") in collapsed)
    }

    @Test
    fun `flipping installed engine adds it to collapsed set`() {
        val collapsed = computeCollapsedEngines(
            installedEngines = setOf(VoiceFamilyIds.PIPER),
            availableEngines = emptySet(),
            flipped = setOf("installed:Piper"),
        )
        assertEquals(
            setOf(EngineCollapseKey(VoiceLibrarySection.Installed, "Piper")),
            collapsed,
        )
    }

    @Test
    fun `flipping available engine removes it from collapsed set`() {
        val collapsed = computeCollapsedEngines(
            installedEngines = emptySet(),
            availableEngines = setOf(VoiceFamilyIds.KOKORO),
            flipped = setOf("available:Kokoro"),
        )
        // Available + flipped = expanded → not in the collapsed set.
        assertTrue(collapsed.isEmpty())
    }

    @Test
    fun `engines absent from a section are never emitted even when flipped`() {
        // User flipped both Installed engines but Kokoro isn't installed —
        // the helper must not emit a key for an engine the user can't see.
        val collapsed = computeCollapsedEngines(
            installedEngines = setOf(VoiceFamilyIds.PIPER),
            availableEngines = emptySet(),
            flipped = setOf("installed:Piper", "installed:Kokoro"),
        )
        assertEquals(
            setOf(EngineCollapseKey(VoiceLibrarySection.Installed, "Piper")),
            collapsed,
        )
    }

    @Test
    fun `mixed flipping under both sections resolves independently`() {
        val collapsed = computeCollapsedEngines(
            installedEngines = setOf(VoiceFamilyIds.PIPER, VoiceFamilyIds.KOKORO),
            availableEngines = setOf(VoiceFamilyIds.PIPER, VoiceFamilyIds.KOKORO),
            // Flip Installed Piper (now collapsed) and Available Kokoro
            // (now expanded). Leave the other two at their defaults.
            flipped = setOf("installed:Piper", "available:Kokoro"),
        )

        // Installed Piper: flipped from default-expanded → collapsed.
        assertTrue(EngineCollapseKey(VoiceLibrarySection.Installed, "Piper") in collapsed)
        // Installed Kokoro: default expanded.
        assertFalse(EngineCollapseKey(VoiceLibrarySection.Installed, "Kokoro") in collapsed)
        // Available Piper: default collapsed → in the set.
        assertTrue(EngineCollapseKey(VoiceLibrarySection.Available, "Piper") in collapsed)
        // Available Kokoro: flipped from default-collapsed → expanded → not in.
        assertFalse(EngineCollapseKey(VoiceLibrarySection.Available, "Kokoro") in collapsed)
    }

    @Test
    fun `built-in collapse tokens keep the pre-1500 on-disk names`() {
        // The persisted collapse keys were `<section>:<EnumName>` before the
        // feature-local engine enum retired (#1500). The descriptor tokens
        // MUST keep producing those exact strings or saved state resets.
        val expected = mapOf(
            VoiceFamilyIds.PIPER to "installed:Piper",
            VoiceFamilyIds.KOKORO to "installed:Kokoro",
            VoiceFamilyIds.KITTEN to "installed:Kitten",
            VoiceFamilyIds.SUPERTONIC to "installed:Supertonic",
            VoiceFamilyIds.AZURE to "installed:Azure",
            VoiceFamilyIds.SYSTEM_TTS to "installed:SystemTts",
        )
        for ((family, storeKey) in expected) {
            assertEquals(storeKey, collapseKeyFor(VoiceLibrarySection.Installed, family).storeKey())
        }
    }

    @Test
    fun `an unknown plugin engine collapses under its own family id`() {
        val collapsed = computeCollapsedEngines(
            installedEngines = emptySet(),
            availableEngines = setOf("voice_whisper"),
            flipped = emptySet(),
        )
        assertEquals(
            setOf(EngineCollapseKey(VoiceLibrarySection.Available, "voice_whisper")),
            collapsed,
        )
    }
}
