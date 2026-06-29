package `in`.jphe.storyvox.playback.voice

/**
 * Issue #1283 — pure resolution of which voice narrates a given line.
 *
 * Resolution order:
 *   1. the speaker's explicitly-assigned **character voice**, if any;
 *   2. the fiction's **narrator default** (`Fiction.pinnedVoiceId`), if set;
 *   3. the **global active voice** (always present — it's the engine's
 *      current voice).
 *
 * [speaker] is null for narration (un-attributed text), which resolves to
 * the narrator default / global voice. Stateless + dependency-free so it
 * unit-tests without the DB or the engine.
 */
object CharacterVoiceResolver {
    fun resolve(
        speaker: String?,
        characterVoices: Map<String, String>,
        narratorVoiceId: String?,
        globalVoiceId: String,
    ): String =
        speaker?.let { characterVoices[it] } ?: narratorVoiceId ?: globalVoiceId
}
