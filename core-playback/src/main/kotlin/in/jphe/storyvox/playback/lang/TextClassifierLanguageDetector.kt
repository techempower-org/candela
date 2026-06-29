package `in`.jphe.storyvox.playback.lang

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLanguage
import androidx.annotation.RequiresApi

/**
 * Issue #1233 — [LanguageDetector] backed by Android's on-device
 * `TextClassifier.detectLanguage`.
 *
 * ### API level
 *
 * `TextClassifier.detectLanguage(TextLanguage.Request)` is **API 29+**
 * (Android 10) — note the `TextClassifier` *interface* exists from API 26,
 * but the language-detection method does not. The app's `minSdk` is 26,
 * so on API 26–28 [create] returns a detector whose [detect] always
 * returns `null`. That's not a defect: a `null` detection is exactly the
 * "stay on the current voice" fallback, so the feature simply no-ops on
 * older devices instead of needing a separate code path.
 *
 * ### Robustness
 *
 * Everything is wrapped so a "don't know" — short text, no hypothesis,
 * low confidence, a `TextClassifier` that throws — collapses to `null`.
 * This runs on the audio producer thread inside the engine mutex
 * (see `EnginePlayer.activeVoiceEngineHandle`), so it must never throw
 * into the synth path.
 *
 * @property classifier the platform classifier, or `null` on API < 29 /
 *   when the system service is unavailable.
 * @property minTextLength reject text shorter than this — single words
 *   classify unreliably (#1233).
 * @property minConfidence reject detections below this confidence.
 */
class TextClassifierLanguageDetector internal constructor(
    private val classifier: TextClassifier?,
    private val minTextLength: Int = DEFAULT_MIN_TEXT_LENGTH,
    private val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
) : LanguageDetector {

    override fun detect(text: String): DetectedLanguage? {
        if (text.length < minTextLength) return null
        val classifier = classifier ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { detectApi29(classifier, text) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun detectApi29(classifier: TextClassifier, text: String): DetectedLanguage? {
        val request = TextLanguage.Request.Builder(text).build()
        val result: TextLanguage = classifier.detectLanguage(request)
        if (result.localeHypothesisCount <= 0) return null
        // Hypotheses come ranked best-first; take the top one.
        val locale = result.getLocale(0)
        val confidence = result.getConfidenceScore(locale)
        return decideDetection(
            rawLanguageTag = locale.toLanguageTag(),
            confidence = confidence,
            textLength = text.length,
            minTextLength = minTextLength,
            minConfidence = minConfidence,
        )
    }

    companion object {
        /** ~3–4 words; below this, single-word false positives dominate. */
        const val DEFAULT_MIN_TEXT_LENGTH: Int = 16

        /** Conservative floor — embedded foreign phrases that the model is
         *  unsure about read fine in the primary voice, so only act on
         *  confident detections. */
        const val DEFAULT_MIN_CONFIDENCE: Float = 0.55f

        /**
         * Build a detector from a [Context]. Returns a no-op detector
         * (always `null`) on API < 29 or when the system can't provide a
         * `TextClassifier`, so callers get a single non-null
         * [LanguageDetector] regardless of device.
         */
        fun create(
            context: Context,
            minTextLength: Int = DEFAULT_MIN_TEXT_LENGTH,
            minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
        ): TextClassifierLanguageDetector {
            val classifier: TextClassifier? = runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    null
                } else {
                    // #1265 — resolve the manager off the application context so
                    // we never pin a short-lived context (the detector keeps the
                    // TextClassifier, not the Context, so this is defensive).
                    context.applicationContext
                        .getSystemService(TextClassificationManager::class.java)
                        ?.textClassifier
                }
            }.getOrNull()
            return TextClassifierLanguageDetector(classifier, minTextLength, minConfidence)
        }
    }
}
