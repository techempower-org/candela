package `in`.jphe.storyvox.playback.lang

import `in`.jphe.storyvox.playback.voice.CatalogEntry
import `in`.jphe.storyvox.playback.voice.VoiceGender
import `in`.jphe.storyvox.playback.voice.voiceFamilyId

/**
 * Issue #1233 — pure decision logic that maps a detected language to the
 * best voice for narrating it.
 *
 * No Android, no I/O, no engine state — just `(detected language, current
 * voice, candidate catalog) -> CatalogEntry?`. That makes the whole
 * routing policy directly unit-testable, and keeps it deterministic,
 * which the on-disk PCM cache relies on (a cache hit replays the voices a
 * previous render chose, so identical inputs must route identically).
 *
 * ### Policy
 *
 * 1. **Same-family only.** A candidate must share the current voice's
 *    engine family ([voiceFamilyId]). Switching among Kokoro's 53
 *    shared-model speakers is a reload-free `setActiveSpeakerId`;
 *    crossing to another family would force a model download / multi-second
 *    reload mid-chapter, which is a worse experience than just reading the
 *    foreign passage in the current voice. Cross-family routing is a
 *    deliberate non-goal here.
 * 2. **No-op when already matching.** If the detected language already
 *    matches the current voice's language, return `null` — nothing to do.
 * 3. **Graceful fallback.** If no same-family voice exists for the
 *    detected language, return `null` and the caller stays on the current
 *    voice (#1233's required fallback). This is why Piper/Kitten/Azure/
 *    System-TTS voices — none of which ship sibling foreign-language
 *    voices in the catalog — simply never switch.
 * 4. **Best-match ordering** among same-language same-family candidates:
 *    same gender as the current voice first (narration continuity), then
 *    same quality tier, then highest quality, then a stable id tiebreak
 *    for determinism.
 */
object LanguageVoiceRouter {

    /**
     * Resolve the voice to narrate text detected as [detectedLanguage].
     *
     * @param detectedLanguage an ISO-639 base subtag (see [baseLanguage]);
     *   callers should pass [DetectedLanguage.languageCode], which is
     *   already normalised.
     * @param current the voice the listener selected (the primary voice).
     * @param catalog the candidate voices to route among — typically
     *   [`in`.jphe.storyvox.playback.voice.VoiceCatalog.voices].
     * @return the voice to switch to for this language, or `null` to stay
     *   on [current].
     */
    fun route(
        detectedLanguage: String,
        current: CatalogEntry,
        catalog: List<CatalogEntry>,
    ): CatalogEntry? {
        val target = baseLanguage(detectedLanguage)
        if (target.isBlank()) return null

        // Already the current voice's language → nothing to switch to.
        if (target == baseLanguage(current.language)) return null

        val family = current.engineType.voiceFamilyId()
        val candidates = catalog.filter { entry ->
            entry.id != current.id &&
                entry.engineType.voiceFamilyId() == family &&
                baseLanguage(entry.language) == target
        }
        if (candidates.isEmpty()) return null

        return candidates.sortedWith(
            // Best first. Each clause is descending so `true`/higher sorts
            // ahead; the final id clause is the deterministic tiebreak.
            compareByDescending<CatalogEntry> { it.matchesGenderOf(current) }
                .thenByDescending { it.qualityLevel == current.qualityLevel }
                .thenByDescending { it.qualityLevel.ordinal }
                .thenBy { it.id },
        ).first()
    }

    /** Gender continuity: a real (non-[VoiceGender.Unknown]) gender that
     *  equals the current voice's. Unknown never counts as a match so a
     *  multi-speaker corpus entry doesn't out-rank a true gender match. */
    private fun CatalogEntry.matchesGenderOf(current: CatalogEntry): Boolean =
        gender != VoiceGender.Unknown && gender == current.gender
}
