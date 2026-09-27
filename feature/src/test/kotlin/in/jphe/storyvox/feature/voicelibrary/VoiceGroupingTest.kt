package `in`.jphe.storyvox.feature.voicelibrary

import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [groupByEngineThenTier]. The grouping helper is the
 * load-bearing piece of issue #94 — it decides the read order of the
 * voice library (engine first, then tier within each engine). Anything
 * that breaks this ordering is user-visible, so the contract is pinned
 * here in JVM-only tests that don't bring up the screen.
 */
class VoiceGroupingTest {

    private fun piper(id: String, tier: QualityLevel) = UiVoiceInfo(
        id = id,
        displayName = id,
        language = "en_US",
        sizeBytes = 0L,
        isInstalled = true,
        qualityLevel = tier,
        engineType = EngineType.Piper,
    )

    private fun kokoro(id: String, tier: QualityLevel, speakerId: Int = 0) = UiVoiceInfo(
        id = id,
        displayName = id,
        language = "en_US",
        sizeBytes = 0L,
        isInstalled = true,
        qualityLevel = tier,
        engineType = EngineType.Kokoro(speakerId),
    )

    @Test
    fun `empty input yields empty map`() {
        assertTrue(emptyList<UiVoiceInfo>().groupByEngineThenTier().isEmpty())
    }

    @Test
    fun `mixed input groups Piper first then Kokoro`() {
        val grouped = listOf(
            kokoro("k1", QualityLevel.High),
            piper("p1", QualityLevel.Medium),
        ).groupByEngineThenTier()

        // Outer iteration order matters — Piper must be first regardless
        // of input order so the screen renders Piper section first.
        assertEquals(
            listOf(VoiceFamilyIds.PIPER, VoiceFamilyIds.KOKORO),
            grouped.keys.toList(),
        )
    }

    @Test
    fun `Piper inner order is ascending Low Medium High`() {
        val grouped = listOf(
            piper("p-high", QualityLevel.High),
            piper("p-low", QualityLevel.Low),
            piper("p-med", QualityLevel.Medium),
        ).groupByEngineThenTier()

        val piperTiers = grouped[VoiceFamilyIds.PIPER]?.keys?.toList()
        assertEquals(
            listOf(QualityLevel.Low, QualityLevel.Medium, QualityLevel.High),
            piperTiers,
        )
    }

    @Test
    fun `Kokoro inner order has Studio first then High`() {
        val grouped = listOf(
            kokoro("k-high", QualityLevel.High),
            kokoro("k-studio", QualityLevel.Studio),
        ).groupByEngineThenTier()

        val kokoroTiers = grouped[VoiceFamilyIds.KOKORO]?.keys?.toList()
        assertEquals(
            listOf(QualityLevel.Studio, QualityLevel.High),
            kokoroTiers,
        )
    }

    @Test
    fun `Piper-only input has no Kokoro key`() {
        val grouped = listOf(
            piper("p1", QualityLevel.Low),
            piper("p2", QualityLevel.Medium),
        ).groupByEngineThenTier()

        assertTrue(grouped.containsKey(VoiceFamilyIds.PIPER))
        assertFalse(
            "Kokoro key must not appear when no Kokoro voices in input",
            grouped.containsKey(VoiceFamilyIds.KOKORO),
        )
    }

    @Test
    fun `Kokoro-only input has no Piper key`() {
        val grouped = listOf(
            kokoro("k1", QualityLevel.Studio),
            kokoro("k2", QualityLevel.High, speakerId = 1),
        ).groupByEngineThenTier()

        assertTrue(grouped.containsKey(VoiceFamilyIds.KOKORO))
        assertFalse(
            "Piper key must not appear when no Piper voices in input",
            grouped.containsKey(VoiceFamilyIds.PIPER),
        )
    }

    @Test
    fun `empty tier within an engine is omitted`() {
        // Piper input that only has Medium voices — Low and High keys
        // must not appear under Piper. Hollow tier headers in the UI are
        // exactly what #94 is trying to avoid.
        val grouped = listOf(
            piper("p-med-1", QualityLevel.Medium),
            piper("p-med-2", QualityLevel.Medium),
        ).groupByEngineThenTier()

        val piperTiers = grouped[VoiceFamilyIds.PIPER]?.keys ?: emptySet()
        assertEquals(setOf(QualityLevel.Medium), piperTiers)
    }

    @Test
    fun `source order preserved within a tier`() {
        // The catalog curates the inside-tier order; re-sorting here
        // would lose that. Verify input order is mirrored on output.
        val a = piper("p-med-a", QualityLevel.Medium)
        val b = piper("p-med-b", QualityLevel.Medium)
        val c = piper("p-med-c", QualityLevel.Medium)
        val grouped = listOf(c, a, b).groupByEngineThenTier()

        assertEquals(
            listOf(c, a, b),
            grouped[VoiceFamilyIds.PIPER]?.get(QualityLevel.Medium),
        )
    }

    @Test
    fun `full mixed scenario lays out cleanly`() {
        // Realistic shape: a few Piper voices across all three tiers,
        // a Studio Kokoro and a non-Studio Kokoro. Walks the full
        // contract end-to-end: outer order, inner orders, omission of
        // empty tiers, source-order preservation.
        val pLow = piper("p-low", QualityLevel.Low)
        val pMed1 = piper("p-med-1", QualityLevel.Medium)
        val pMed2 = piper("p-med-2", QualityLevel.Medium)
        val pHigh = piper("p-high", QualityLevel.High)
        val kStudio = kokoro("k-studio", QualityLevel.Studio)
        val kHigh = kokoro("k-high", QualityLevel.High, speakerId = 1)

        // Shuffle the input so we know the helper isn't relying on
        // an already-sorted list.
        val grouped = listOf(kHigh, pHigh, pMed1, kStudio, pLow, pMed2)
            .groupByEngineThenTier()

        assertEquals(
            listOf(VoiceFamilyIds.PIPER, VoiceFamilyIds.KOKORO),
            grouped.keys.toList(),
        )

        val piper = grouped.getValue(VoiceFamilyIds.PIPER)
        assertEquals(
            listOf(QualityLevel.Low, QualityLevel.Medium, QualityLevel.High),
            piper.keys.toList(),
        )
        assertEquals(listOf(pLow), piper[QualityLevel.Low])
        assertEquals(listOf(pMed1, pMed2), piper[QualityLevel.Medium])
        assertEquals(listOf(pHigh), piper[QualityLevel.High])

        val kokoro = grouped.getValue(VoiceFamilyIds.KOKORO)
        assertEquals(
            listOf(QualityLevel.Studio, QualityLevel.High),
            kokoro.keys.toList(),
        )
        assertEquals(listOf(kStudio), kokoro[QualityLevel.Studio])
        assertEquals(listOf(kHigh), kokoro[QualityLevel.High])
    }
}
