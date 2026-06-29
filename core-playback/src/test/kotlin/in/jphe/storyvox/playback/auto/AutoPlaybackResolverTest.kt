package `in`.jphe.storyvox.playback.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1232 — covers [AutoPlaybackResolver.bestTitleMatch], the voice-search
 * ranking that turns "play <title>" into a library fiction id. The repo-backed
 * `resolve()` paths are thin delegations over Room and are validated on-device
 * (DHU) per the issue's testing notes; the ranking is the part worth pinning.
 */
class AutoPlaybackResolverTest {

    private val library = listOf(
        "f-pride" to "Pride and Prejudice",
        "f-prejudice" to "Prejudice Revisited",
        "f-mistborn" to "Mistborn",
        "f-wax" to "The Wax and Wayne Saga",
    )

    @Test
    fun `exact title wins over prefix and substring`() {
        // "mistborn" is an exact hit; nothing else contains it.
        assertEquals("f-mistborn", AutoPlaybackResolver.bestTitleMatch("Mistborn", library))
    }

    @Test
    fun `prefix beats substring`() {
        // "pride" is a prefix of "Pride and Prejudice" (rank 2) and a substring
        // of "Prejudice Revisited"? no — only the first contains it as prefix.
        assertEquals("f-pride", AutoPlaybackResolver.bestTitleMatch("pride", library))
    }

    @Test
    fun `match is case-insensitive`() {
        assertEquals("f-mistborn", AutoPlaybackResolver.bestTitleMatch("MISTBORN", library))
        assertEquals("f-pride", AutoPlaybackResolver.bestTitleMatch("pride AND prejudice", library))
    }

    @Test
    fun `substring matches when no prefix does`() {
        // "wayne" appears mid-title only.
        assertEquals("f-wax", AutoPlaybackResolver.bestTitleMatch("wayne", library))
    }

    @Test
    fun `prefix is preferred even when a substring candidate comes first`() {
        val candidates = listOf(
            "f-sub" to "A Prequel to Prejudice", // contains "prejudice" (substring, rank 1)
            "f-pre" to "Prejudice and Pride",     // starts with "prejudice" (prefix, rank 2)
        )
        assertEquals("f-pre", AutoPlaybackResolver.bestTitleMatch("prejudice", candidates))
    }

    @Test
    fun `no match returns null`() {
        assertNull(AutoPlaybackResolver.bestTitleMatch("nonexistent saga", library))
    }

    @Test
    fun `blank query returns null`() {
        assertNull(AutoPlaybackResolver.bestTitleMatch("   ", library))
        assertNull(AutoPlaybackResolver.bestTitleMatch("", library))
    }

    @Test
    fun `first candidate wins on equal rank`() {
        val candidates = listOf(
            "f-a" to "Dune",
            "f-b" to "Dune Messiah", // also contains "dune" but "f-a" is exact
        )
        assertEquals("f-a", AutoPlaybackResolver.bestTitleMatch("dune", candidates))
        // Two equal-rank prefixes → first wins.
        val twoPrefixes = listOf(
            "f-1" to "Star Wars",
            "f-2" to "Star Trek",
        )
        assertEquals("f-1", AutoPlaybackResolver.bestTitleMatch("star", twoPrefixes))
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(AutoPlaybackResolver.bestTitleMatch("anything", emptyList()))
    }
}
