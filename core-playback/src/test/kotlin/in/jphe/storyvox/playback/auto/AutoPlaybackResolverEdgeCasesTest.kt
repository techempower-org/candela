package `in`.jphe.storyvox.playback.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1315 — additional edge cases for [AutoPlaybackResolver.bestTitleMatch],
 * complementing [AutoPlaybackResolverTest] (which covers the core exact > prefix
 * > substring rank order). Focus: query trimming, empty/blank titles,
 * query-longer-than-title, and rank precedence independent of candidate order.
 *
 * Scope note: the repo-backed `resolve()` paths are intentionally not unit-tested
 * here — per #1232 they are thin delegations over Room validated on-device (DHU),
 * and there is no mocking library in this module to stub the four repositories.
 * Unknown / malformed media ids ("no such node") are already covered by
 * [AutoMediaIdTest] ("unknown or malformed ids parse to null"). This file stays
 * on the pure, JVM-testable matcher surface.
 */
class AutoPlaybackResolverEdgeCasesTest {

    private val library = listOf(
        "f-pride" to "Pride and Prejudice",
        "f-mist" to "Mistborn",
    )

    @Test fun `surrounding whitespace in a non-blank query is trimmed before matching`() {
        assertEquals("f-mist", AutoPlaybackResolver.bestTitleMatch("  mistborn  ", library))
    }

    @Test fun `a query longer than every title matches nothing`() {
        assertNull(AutoPlaybackResolver.bestTitleMatch("Pride and Prejudice and Zombies", library))
    }

    @Test fun `a candidate with an empty title is never matched`() {
        val candidates = listOf("f-empty" to "", "f-real" to "Dune")
        assertNull(AutoPlaybackResolver.bestTitleMatch("x", candidates))
        // ...but a real match alongside the empty-title candidate still resolves.
        assertEquals("f-real", AutoPlaybackResolver.bestTitleMatch("dune", candidates))
    }

    @Test fun `an empty query returns null even against an empty title`() {
        // q is "" after trim → early null guard, so a "" title can't spuriously rank.
        assertNull(AutoPlaybackResolver.bestTitleMatch("", listOf("f-empty" to "")))
    }

    @Test fun `an exact match outranks an earlier prefix candidate`() {
        val candidates = listOf(
            "f-prefix" to "Dune Messiah", // startsWith "dune" → rank 2, listed first
            "f-exact" to "Dune",          // == "dune" → rank 3, listed second
        )
        assertEquals("f-exact", AutoPlaybackResolver.bestTitleMatch("dune", candidates))
    }

    @Test fun `a substring-only match is found when nothing ranks higher`() {
        val candidates = listOf("f-saga" to "The Wax and Wayne Saga")
        assertEquals("f-saga", AutoPlaybackResolver.bestTitleMatch("wax and wayne", candidates))
    }
}
