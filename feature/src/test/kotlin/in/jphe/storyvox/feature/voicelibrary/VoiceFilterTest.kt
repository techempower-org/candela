package `in`.jphe.storyvox.feature.voicelibrary

import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import `in`.jphe.storyvox.playback.voice.VoiceGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #264 — unit tests for the Voice Library search + language
 * filter contract. The helpers ([VoiceFilterCriteria], [matchesCriteria],
 * [filterBy], [primaryLanguageCode]) live in
 * [VoiceFilter] so they're exercisable from a JVM test without
 * Compose / Hilt / Android.
 *
 * Coverage:
 *  1. Query filter is case-insensitive across displayName / language /
 *     engine label, and a blank query passes everything.
 *  2. Language filter matches the **primary** two-letter code, so
 *     en-US / en-GB / en-AU all pass an "en" filter.
 *  3. Combined (query + language) filter is AND-semantics — a voice
 *     has to pass both dimensions to land in the result.
 *  4. Favourite-flag (starred) is preserved across filter changes —
 *     filtering doesn't drop a voice's identity, the filtered list is
 *     just a subset of the same UiVoiceInfo rows.
 */
class VoiceFilterTest {

    // ─── Test fixtures ─────────────────────────────────────────────

    private fun piper(
        id: String,
        displayName: String,
        language: String,
        tier: QualityLevel = QualityLevel.Medium,
        gender: VoiceGender = VoiceGender.Female,
    ) = UiVoiceInfo(
        id = id,
        displayName = displayName,
        language = language,
        sizeBytes = 0L,
        isInstalled = true,
        qualityLevel = tier,
        engineType = EngineType.Piper,
        gender = gender,
    )

    private fun kokoro(
        id: String,
        displayName: String,
        language: String,
        speakerId: Int = 0,
    ) = UiVoiceInfo(
        id = id,
        displayName = displayName,
        language = language,
        sizeBytes = 0L,
        isInstalled = true,
        qualityLevel = QualityLevel.High,
        engineType = EngineType.Kokoro(speakerId),
        gender = VoiceGender.Female,
    )

    private fun azure(
        id: String,
        displayName: String,
        language: String,
    ) = UiVoiceInfo(
        id = id,
        displayName = displayName,
        language = language,
        sizeBytes = 0L,
        isInstalled = false,
        qualityLevel = QualityLevel.Studio,
        engineType = EngineType.Azure(voiceName = id, region = "eastus"),
        gender = VoiceGender.Female,
    )

    private val catalog = listOf(
        piper("p_lessac", "Lessac", "en_US"),
        piper("p_cori", "Cori", "en_GB"),
        kokoro("k_aoede", "Aoede", "en_US", speakerId = 1),
        kokoro("k_lena", "Lena", "de_DE", speakerId = 2),
        azure("az_aria", "Aria", "en_US"),
        azure("az_davis", "Davis", "en_US"),
        azure("az_carmen", "Carmen", "es_MX"),
        azure("az_henri", "Henri", "fr_FR"),
        azure("az_ada", "Ada Multilingual", "en_US"),
    )

    // ─── Test 1: query filter — case-insensitive substring ─────────

    @Test
    fun `query filter matches displayName case-insensitively`() {
        // "ARIA" (uppercase) must still match the "Aria" Azure voice.
        // JP's spec specifically calls out type-to-filter on display
        // name; case-insensitivity is the difference between "users can
        // find a voice while typing fast" and "filter only works when
        // capitalization happens to match".
        val crit = VoiceFilterCriteria(query = "ARIA")
        val hits = catalog.filterBy(crit)

        assertEquals(1, hits.size)
        assertEquals("az_aria", hits.single().id)
    }

    @Test
    fun `query filter matches language tag substring`() {
        // Typing "en_GB" should find Cori (the only en_GB voice). This
        // is the only path users have to surface a voice by locale
        // without going through the language chip — important fallback
        // for less-common codes that don't appear as chips.
        val crit = VoiceFilterCriteria(query = "en_GB")
        val hits = catalog.filterBy(crit)

        assertEquals(setOf("p_cori"), hits.map { it.id }.toSet())
    }

    @Test
    fun `query filter matches engine label`() {
        // Typing "kokoro" surfaces every Kokoro voice. This is one of
        // the three places #264 explicitly says the search box should
        // hit (displayName / language / engine).
        val crit = VoiceFilterCriteria(query = "kokoro")
        val hits = catalog.filterBy(crit)

        assertEquals(setOf("k_aoede", "k_lena"), hits.map { it.id }.toSet())
    }

    @Test
    fun `blank query passes every voice`() {
        // Empty / whitespace-only query is a no-op — every catalog row
        // passes. Test the trim() boundary explicitly.
        assertEquals(catalog.size, catalog.filterBy(VoiceFilterCriteria(query = "")).size)
        assertEquals(catalog.size, catalog.filterBy(VoiceFilterCriteria(query = "   ")).size)
    }

    // ─── Test 2: language filter — exact two-letter match ─────────

    @Test
    fun `language filter matches the primary two-letter code`() {
        // en-US / en-GB / en-AU all roll up under "en" — six English
        // voices in the catalog (2 Piper + 1 Kokoro + 3 Azure).
        val crit = VoiceFilterCriteria(language = "en")
        val hits = catalog.filterBy(crit)

        assertEquals(
            setOf("p_lessac", "p_cori", "k_aoede", "az_aria", "az_davis", "az_ada"),
            hits.map { it.id }.toSet(),
        )
    }

    @Test
    fun `language filter narrowing to a single-region code`() {
        // "es" picks up Carmen (es_MX) and only Carmen.
        val crit = VoiceFilterCriteria(language = "es")
        val hits = catalog.filterBy(crit)

        assertEquals(setOf("az_carmen"), hits.map { it.id }.toSet())
    }

    @Test
    fun `primary language code extraction handles all catalog locales`() {
        assertEquals("en", piper("x", "X", "en_US").primaryLanguageCode())
        assertEquals("en", piper("x", "X", "en_GB").primaryLanguageCode())
        assertEquals("de", piper("x", "X", "de_DE").primaryLanguageCode())
        assertEquals("zh", piper("x", "X", "zh_CN").primaryLanguageCode())
        // Empty or short language strings fall back gracefully.
        assertEquals("", piper("x", "X", "").primaryLanguageCode())
        assertEquals("e", piper("x", "X", "e").primaryLanguageCode())
    }

    @Test
    fun `null language filter passes every voice`() {
        // null = "no chip selected" — no narrowing on this dimension.
        val hits = catalog.filterBy(VoiceFilterCriteria(language = null))
        assertEquals(catalog.size, hits.size)
    }

    // ─── Test 3: combined filter — AND semantics ───────────────────

    @Test
    fun `combined query and language filter AND-semantics`() {
        // Query "az_" + language "en" = Aria + Davis + Ada (all the
        // Azure voices with English locale). Excludes Carmen
        // (Spanish) and Henri (French) even though they have "az_"
        // prefixed IDs — wait, "az_" isn't in displayName. Let me use
        // a real query: "neural" — none in the test catalog. Use
        // "a" with lang "en" so it pulls Aria + Ada (both start
        // with A and en_US) plus Aoede (Kokoro, en_US, contains 'a').
        val crit = VoiceFilterCriteria(query = "a", language = "en")
        val hits = catalog.filterBy(crit)

        // Voices with "a" in displayName AND en_* language:
        //  - p_lessac → "Lessac" has 'a', en_US ✓
        //  - p_cori → "Cori" no 'a', skip
        //  - k_aoede → "Aoede" has 'a', en_US ✓
        //  - az_aria → "Aria" has 'a', en_US ✓
        //  - az_davis → "Davis" has 'a', en_US ✓
        //  - az_carmen → "Carmen" has 'a', es_MX — fails LANG ✗
        //  - az_henri → "Henri" no 'a', fr_FR — fails both
        //  - az_ada → "Ada Multilingual" has 'a', en_US ✓
        // → 5 hits.
        assertEquals(
            setOf("p_lessac", "k_aoede", "az_aria", "az_davis", "az_ada"),
            hits.map { it.id }.toSet(),
        )
    }

    @Test
    fun `combined filter with no matches yields empty list`() {
        // Query "xyz" + lang "en" — no English voice has "xyz".
        val crit = VoiceFilterCriteria(query = "xyz", language = "en")
        assertTrue(catalog.filterBy(crit).isEmpty())
    }

    @Test
    fun `combined filter degenerates to language-only when query is blank`() {
        val langOnly = catalog.filterBy(VoiceFilterCriteria(query = "", language = "en"))
        val bothSet = catalog.filterBy(VoiceFilterCriteria(query = "  ", language = "en"))
        assertEquals(langOnly.map { it.id }.toSet(), bothSet.map { it.id }.toSet())
    }

    // ─── Test 4: star-pinned voice persistence ─────────────────────

    @Test
    fun `starred voice identity is preserved across filter changes`() {
        // The "favorites" bucket is a [List<UiVoiceInfo>] of starred
        // rows. Applying the search filter to the same bucket must
        // return a *subset* of identical row objects (same ids, same
        // displayName, same isInstalled, same engine) — filtering must
        // never mutate or replace a row. This is the contract the
        // screen relies on for the star toggle to keep working when
        // the user types a query and the favorite happens to still
        // match: tapping the star removes the voice from
        // favoriteIds, not from the filtered list itself.
        val starred = listOf(
            catalog.first { it.id == "p_lessac" },
            catalog.first { it.id == "k_aoede" },
            catalog.first { it.id == "az_carmen" },
        )

        // Filter: query "le" — should keep Lessac, drop the others.
        val afterQuery = starred.filterBy(VoiceFilterCriteria(query = "le"))
        assertEquals(1, afterQuery.size)
        // Identity preserved — the returned object is the same instance.
        assertTrue(
            "Filter must not duplicate or copy starred rows",
            afterQuery.single() === starred.first { it.id == "p_lessac" },
        )

        // Filter: language "en" — keeps Lessac and Aoede, drops Carmen.
        val afterLang = starred.filterBy(VoiceFilterCriteria(language = "en"))
        assertEquals(setOf("p_lessac", "k_aoede"), afterLang.map { it.id }.toSet())
        // Identity preserved across the second filter pass too.
        assertTrue(afterLang.all { row -> row === starred.first { it.id == row.id } })

        // Clearing both filters: the original starred list comes back
        // verbatim (pass-through path).
        val afterClear = starred.filterBy(VoiceFilterCriteria())
        assertEquals(starred, afterClear)
    }

    @Test
    fun `nested map filter drops empty engine and tier buckets`() {
        // The screen relies on the nested-map filter to also collapse
        // empty buckets so section headers don't render with zero rows
        // underneath. Exercise the path: filter to language "en",
        // confirm the German Kokoro engine bucket disappears.
        val grouped: Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>> = catalog
            .groupByEngineThenTier()
        val filtered = grouped.filterBy(VoiceFilterCriteria(language = "de"))

        // de = only Lena (Kokoro). Piper bucket must be gone.
        assertFalse(VoiceFamilyIds.PIPER in filtered)
        assertFalse(VoiceFamilyIds.AZURE in filtered)
        assertTrue(VoiceFamilyIds.KOKORO in filtered)
        val kokoroTiers = filtered.getValue(VoiceFamilyIds.KOKORO)
        // Lena is High tier; no other Kokoro tier should appear.
        assertEquals(setOf(QualityLevel.High), kokoroTiers.keys)
        assertEquals(setOf("k_lena"), kokoroTiers.getValue(QualityLevel.High).map { it.id }.toSet())
    }

    @Test
    fun `combined filter respects gender and tier secondary chips`() {
        // The screen-local gender / tier / multilingual chips still
        // apply through the same VoiceFilterCriteria — exercise the
        // five-dimension AND to pin the contract.
        val crit = VoiceFilterCriteria(
            language = "en",
            genders = setOf(VoiceGender.Female),
            tiers = setOf(QualityLevel.Studio),
        )
        val hits = catalog.filterBy(crit)
        // English + Female + Studio = the three Azure voices (all are
        // Female + Studio in the fixture).
        assertEquals(
            setOf("az_aria", "az_davis", "az_ada"),
            hits.map { it.id }.toSet(),
        )
    }

    @Test
    fun `multilingualOnly chip narrows to Multilingual-named voices`() {
        val crit = VoiceFilterCriteria(multilingualOnly = true)
        val hits = catalog.filterBy(crit)
        assertEquals(setOf("az_ada"), hits.map { it.id }.toSet())
    }
}
