package `in`.jphe.storyvox.feature.voicelibrary

import `in`.jphe.storyvox.playback.voice.EngineCollapseKey
import `in`.jphe.storyvox.playback.voice.VoiceEngineId
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
            installedEngines = setOf(VoiceEngine.Piper, VoiceEngine.Kokoro),
            availableEngines = setOf(VoiceEngine.Piper, VoiceEngine.Kokoro),
            flipped = emptySet(),
        )

        // Available defaults to collapsed → both available engines in.
        assertTrue(EngineCollapseKey(VoiceLibrarySection.Available, VoiceEngineId.Piper) in collapsed)
        assertTrue(EngineCollapseKey(VoiceLibrarySection.Available, VoiceEngineId.Kokoro) in collapsed)
        // Installed defaults to expanded → neither installed engine in.
        assertFalse(EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Piper) in collapsed)
        assertFalse(EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Kokoro) in collapsed)
    }

    @Test
    fun `flipping installed engine adds it to collapsed set`() {
        val collapsed = computeCollapsedEngines(
            installedEngines = setOf(VoiceEngine.Piper),
            availableEngines = emptySet(),
            flipped = setOf("installed:Piper"),
        )
        assertEquals(
            setOf(EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Piper)),
            collapsed,
        )
    }

    @Test
    fun `flipping available engine removes it from collapsed set`() {
        val collapsed = computeCollapsedEngines(
            installedEngines = emptySet(),
            availableEngines = setOf(VoiceEngine.Kokoro),
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
            installedEngines = setOf(VoiceEngine.Piper),
            availableEngines = emptySet(),
            flipped = setOf("installed:Piper", "installed:Kokoro"),
        )
        assertEquals(
            setOf(EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Piper)),
            collapsed,
        )
    }

    @Test
    fun `mixed flipping under both sections resolves independently`() {
        val collapsed = computeCollapsedEngines(
            installedEngines = setOf(VoiceEngine.Piper, VoiceEngine.Kokoro),
            availableEngines = setOf(VoiceEngine.Piper, VoiceEngine.Kokoro),
            // Flip Installed Piper (now collapsed) and Available Kokoro
            // (now expanded). Leave the other two at their defaults.
            flipped = setOf("installed:Piper", "available:Kokoro"),
        )

        // Installed Piper: flipped from default-expanded → collapsed.
        assertTrue(EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Piper) in collapsed)
        // Installed Kokoro: default expanded.
        assertFalse(EngineCollapseKey(VoiceLibrarySection.Installed, VoiceEngineId.Kokoro) in collapsed)
        // Available Piper: default collapsed → in the set.
        assertTrue(EngineCollapseKey(VoiceLibrarySection.Available, VoiceEngineId.Piper) in collapsed)
        // Available Kokoro: flipped from default-collapsed → expanded → not in.
        assertFalse(EngineCollapseKey(VoiceLibrarySection.Available, VoiceEngineId.Kokoro) in collapsed)
    }

    @Test
    fun `engine-to-core mapping round trips`() {
        // The (feature) VoiceEngine → (core) VoiceEngineId mapping is
        // load-bearing for the collapse keys. Pin it in tests so a
        // future enum reorder can't silently break persistence.
        assertEquals(VoiceEngineId.Piper, VoiceEngine.Piper.toCoreId())
        assertEquals(VoiceEngineId.Kokoro, VoiceEngine.Kokoro.toCoreId())
        assertEquals(VoiceEngine.Piper, VoiceEngineId.Piper.toFeatureEngine())
        assertEquals(VoiceEngine.Kokoro, VoiceEngineId.Kokoro.toFeatureEngine())
    }
}
