package `in`.jphe.storyvox.playback.lang

/**
 * Issue #1233 — the detected language of a span of text.
 *
 * @property languageCode ISO-639 base language subtag, lower-cased and
 *   stripped of any region/script suffix (e.g. `"fr"`, `"ja"`, `"zh"`).
 *   Normalised at the detector boundary so downstream code
 *   ([LanguageVoiceRouter]) never has to re-parse a BCP-47 tag.
 * @property confidence detector confidence in `0f..1f`.
 */
data class DetectedLanguage(
    val languageCode: String,
    val confidence: Float,
)

/**
 * Issue #1233 — detects the dominant language of a chunk of text so the
 * TTS engine can route foreign passages to a matching voice.
 *
 * Kept as an interface so [`in`.jphe.storyvox.playback.tts.EnginePlayer]
 * depends on the contract, not the Android `TextClassifier` plumbing —
 * which keeps the decision logic ([LanguageVoiceRouter]) and the
 * threshold logic ([decideDetection]) unit-testable on a plain JVM
 * without Robolectric (the project ships no Robolectric — see CLAUDE.md).
 *
 * Implementations MUST be deterministic for a given input within a
 * device/runtime: the on-disk PCM cache key namespaces auto-language
 * renders, and a cache hit replays whatever voices the previous render
 * chose, so the same text must route the same way on re-render.
 */
interface LanguageDetector {
    /**
     * Detect the dominant language of [text]. Returns `null` when the
     * text is too short to classify reliably, the platform can't
     * classify it, confidence is below threshold, or anything throws —
     * every "don't know" collapses to `null` so the caller's fallback
     * (stay on the current voice) is the single, obvious branch.
     */
    fun detect(text: String): DetectedLanguage?
}

/**
 * The pure, Android-free core of the detection decision: given a raw
 * detected language tag, its confidence, and the length of the source
 * text, decide whether the detection is trustworthy enough to act on.
 *
 * Split out from the `TextClassifier`-backed detector so the
 * thresholding rules — the part with actual branching — are unit-tested
 * directly. The `TextClassifier` call itself is a thin, untestable
 * (no Robolectric) shell around this.
 *
 * Rules, in order:
 *  - Text shorter than [minTextLength] is rejected: language detection
 *    on 1–2 words is unreliable (#1233 explicitly calls this out).
 *  - A blank/absent tag is rejected.
 *  - Confidence below [minConfidence] is rejected.
 *
 * @param rawLanguageTag a BCP-47-ish tag from the platform (e.g. `"fr"`,
 *   `"zh-Hant"`), or `null`/blank if the platform returned nothing.
 */
internal fun decideDetection(
    rawLanguageTag: String?,
    confidence: Float,
    textLength: Int,
    minTextLength: Int,
    minConfidence: Float,
): DetectedLanguage? {
    if (textLength < minTextLength) return null
    val base = baseLanguage(rawLanguageTag.orEmpty())
    if (base.isBlank()) return null
    if (confidence < minConfidence) return null
    return DetectedLanguage(languageCode = base, confidence = confidence)
}

/**
 * Normalise a language code to its ISO-639 base subtag: lower-cased,
 * with any region (`fr-FR`, `pt_BR`) or script (`zh-Hant`) suffix
 * dropped. `"zh-Hant"` → `"zh"`, `"en_US"` → `"en"`, `"  FR "` → `"fr"`.
 *
 * Both the detector and [LanguageVoiceRouter] route through this so a
 * detected tag and a catalog `language` ("fr_FR") compare on the same
 * footing.
 */
internal fun baseLanguage(code: String): String =
    code.trim().lowercase().substringBefore('-').substringBefore('_')
