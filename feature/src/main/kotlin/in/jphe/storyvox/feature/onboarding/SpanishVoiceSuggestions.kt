package `in`.jphe.storyvox.feature.onboarding

import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo

/**
 * Issue #1466 — Spanish-first onboarding. The voice step's starter list
 * when the UI is in Spanish, in the order a new listener should see it:
 *
 *  1. **Spanish voices already on the phone** (System TTS, e.g. Google's
 *     `es-US` voice). Zero download, so a family on a tight data plan
 *     hears audio right away. At most [MAX_ON_DEVICE] of them, Latin
 *     American / US Spanish ahead of Castilian, since the audience this
 *     path exists for (#1466) is Spanish-dominant US households.
 *  2. **Kokoro's Spanish speakers** (Dora, Alex) — the neural-quality
 *     option, one shared model download for both.
 *
 * Azure voices are left out on purpose: they need a key the user
 * hasn't set up on first run.
 *
 * Pure so it can be unit-tested without Hilt or a TTS engine
 * ([SpanishVoiceSuggestionsTest]).
 *
 * @param installed [VoiceManager.installedVoices] — System TTS voices
 *   appear here whenever the OS roster lists them.
 * @param available the static catalog ([VoiceManager.availableVoices]).
 *   An installed copy of a Kokoro voice wins over its catalog twin so
 *   the row can skip the download.
 */
internal fun spanishVoiceSuggestions(
    installed: List<UiVoiceInfo>,
    available: List<UiVoiceInfo>,
): List<UiVoiceInfo> {
    val onDevice = installed
        .filter { it.engineType is EngineType.SystemTts && isSpanish(it.language) }
        .sortedBy { spanishRegionRank(it.language) } // stable: keeps roster order within a region
        .distinctBy { it.id }
        .take(MAX_ON_DEVICE)
    val installedById = installed.associateBy { it.id }
    val kokoro = available
        .filter { it.engineType is EngineType.Kokoro && isSpanish(it.language) }
        .map { installedById[it.id] ?: it }
    return onDevice + kokoro
}

/** True for any Spanish locale tag, POSIX (`es_MX`) or BCP-47 (`es-MX`). */
internal fun isSpanish(language: String): Boolean {
    val primary = language.substringBefore('_').substringBefore('-')
    return primary.equals("es", ignoreCase = true)
}

/** Lower ranks first. US and Latin American Spanish lead; Spain last. */
internal fun spanishRegionRank(language: String): Int {
    val region = language.replace('-', '_').substringAfter('_', "").uppercase()
    return when (region) {
        "US" -> 0
        "MX" -> 1
        "419" -> 2
        "ES" -> 4
        else -> 3
    }
}

private const val MAX_ON_DEVICE = 2
