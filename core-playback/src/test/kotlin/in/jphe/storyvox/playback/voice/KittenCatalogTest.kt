package `in`.jphe.storyvox.playback.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #119 — pinning the Kitten section of [VoiceCatalog] against
 * regression. Three properties matter:
 *
 * 1. **Eight entries** — the upstream `kitten-nano-en-v0_8-fp16` model
 *    ships exactly 8 speakers (4 female, 4 male). Adding a 9th by
 *    mistake (or losing one) silently breaks the picker — Kitten is
 *    the smallest tier so a missing voice is a hard-to-spot UX dent.
 *
 * 2. **Speaker IDs 0..7 contiguous** — `KittenEngine.setActiveSpeakerId`
 *    treats the int as a direct index into voices.bin. A gap (e.g. 0,
 *    1, 2, 5, 6) would produce a runtime IndexOutOfBoundsException
 *    inside sherpa-onnx that the caller has no clean way to recover
 *    from, so this guard is load-bearing.
 *
 * 3. **All Low tier + all en_US + sizeBytes = 0L** — Kitten's value
 *    proposition is "smallest tier, on-device English voice for slow
 *    hardware." The Voice Library composes its subtitle around these
 *    fields; a future PR that adds a multilingual Kitten model needs to
 *    relax this guard explicitly rather than passively.
 *
 * Pure-JVM (no Robolectric, no emulator). [VoiceCatalog] is a singleton
 * object built from kotlin-only data, so we can interrogate it directly.
 */
class KittenCatalogTest {

    private val kittenEntries: List<CatalogEntry>
        get() = VoiceCatalog.voices.filter { it.engineType is EngineType.Kitten }

    @Test
    fun `Kitten catalog ships exactly eight speakers`() {
        // sherpa-onnx's kitten-nano-en-v0_8-fp16 release exposes 8 voice
        // embeddings in voices.bin (4 female + 4 male). If upstream adds
        // a 9th OR the catalog accidentally drops one, the picker no
        // longer reflects the model — this guard catches both.
        assertEquals(8, kittenEntries.size)
    }

    @Test
    fun `Kitten speaker ids cover 0 through 7 contiguously`() {
        // KittenEngine.setActiveSpeakerId hands the int straight to
        // sherpa-onnx as a voices.bin row index. A gap or duplicate
        // would produce a runtime crash on voice activation; pinning
        // the contiguous 0..7 invariant here means catalog edits fail
        // CI rather than the picker.
        val speakerIds = kittenEntries
            .map { (it.engineType as EngineType.Kitten).speakerId }
            .toSet()
        assertEquals((0..7).toSet(), speakerIds)
    }

    @Test
    fun `every Kitten entry is en_US Low tier with zero sizeBytes`() {
        // The Kitten section in v0.5.34 is English-only at Low tier
        // (the nano fp16 model). sizeBytes = 0L is the shared-model
        // sentinel that VoiceLibraryScreen reads as "suppress the size
        // chip" (Kokoro uses the same pattern — per-voice byte counts
        // are misleading when one model serves N speakers).
        //
        // If a future PR adds a kitten-mini variant or a multilingual
        // pack, this guard must be relaxed deliberately — not silently
        // edge-cased — so the picker keeps composing subtitles
        // consistently.
        kittenEntries.forEach { entry ->
            assertEquals(
                "Kitten entry ${entry.id} should be Low tier",
                QualityLevel.Low, entry.qualityLevel,
            )
            assertEquals(
                "Kitten entry ${entry.id} should be en_US",
                "en_US", entry.language,
            )
            assertEquals(
                "Kitten entry ${entry.id} should have sizeBytes = 0L (shared model)",
                0L, entry.sizeBytes,
            )
        }
    }

    @Test
    fun `Kitten entries split four female four male`() {
        // The expressivity of the engine comes from the speaker
        // variety — four of each gender matches upstream's
        // expr-voice-N-{f,m} layout. The Voice Library's gender filter
        // chips would shrink to "Female (0)" on a regression that
        // dropped a row.
        val females = kittenEntries.count { it.gender == VoiceGender.Female }
        val males = kittenEntries.count { it.gender == VoiceGender.Male }
        assertEquals("four female Kitten speakers", 4, females)
        assertEquals("four male Kitten speakers", 4, males)
    }

    @Test
    fun `Kitten ids resolve via byId without an Azure roster`() {
        // VoiceCatalog.byId is the pre-PR-4 lookup that the playback
        // path uses to resolve an active voice ID before any Azure
        // roster is fetched. Kitten entries are local, so byId must
        // surface them directly. (byIdWithAzure includes them too —
        // but that's covered by the byId fallback in the production
        // VoiceManager paths.)
        val entry = VoiceCatalog.byId("kitten_f1_en_US_0")
        assertNotNull("byId should find kitten_f1_en_US_0", entry)
        assertTrue(
            "engineType should be Kitten",
            entry!!.engineType is EngineType.Kitten,
        )
        assertEquals(0, (entry.engineType as EngineType.Kitten).speakerId)
    }

    @Test
    fun `EngineType Kitten equality is structural over speakerId`() {
        // Mirrors AzureCatalogTest's structural-equality guard.
        // EngineType.Kitten is a data class, so this is testing the
        // data-class contract — but pinning it here makes the contract
        // load-bearing: VoiceManager's installed-set filter compares
        // by `is EngineType.Kitten`, not by id, so structural equality
        // is what makes "all 8 speakers visible once shared model
        // installed" work.
        val a = EngineType.Kitten(speakerId = 3)
        val b = EngineType.Kitten(speakerId = 3)
        val c = EngineType.Kitten(speakerId = 4)
        assertEquals("same speakerId equal", a, b)
        assertEquals("same hashCode", a.hashCode(), b.hashCode())
        assertFalse("different speakerId not equal", a == c)
    }
}
