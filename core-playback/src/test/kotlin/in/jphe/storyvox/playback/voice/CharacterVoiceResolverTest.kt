package `in`.jphe.storyvox.playback.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1283 — the character → narrator → global resolution order.
 */
class CharacterVoiceResolverTest {

    private val map = mapOf("Alice" to "kokoro_alice", "Bob" to "kokoro_bob")

    @Test
    fun assignedCharacter_winsOverNarratorAndGlobal() {
        assertEquals(
            "kokoro_alice",
            CharacterVoiceResolver.resolve("Alice", map, narratorVoiceId = "narrator_v", globalVoiceId = "global_v"),
        )
    }

    @Test
    fun unassignedSpeaker_fallsBackToNarrator() {
        assertEquals(
            "narrator_v",
            CharacterVoiceResolver.resolve("Stranger", map, narratorVoiceId = "narrator_v", globalVoiceId = "global_v"),
        )
    }

    @Test
    fun narration_nullSpeaker_usesNarrator() {
        assertEquals(
            "narrator_v",
            CharacterVoiceResolver.resolve(null, map, narratorVoiceId = "narrator_v", globalVoiceId = "global_v"),
        )
    }

    @Test
    fun noNarrator_fallsBackToGlobal() {
        assertEquals(
            "global_v",
            CharacterVoiceResolver.resolve("Stranger", map, narratorVoiceId = null, globalVoiceId = "global_v"),
        )
        assertEquals(
            "global_v",
            CharacterVoiceResolver.resolve(null, emptyMap(), narratorVoiceId = null, globalVoiceId = "global_v"),
        )
    }

    @Test
    fun assignedCharacter_winsEvenWhenNarratorNull() {
        assertEquals(
            "kokoro_bob",
            CharacterVoiceResolver.resolve("Bob", map, narratorVoiceId = null, globalVoiceId = "global_v"),
        )
    }
}
