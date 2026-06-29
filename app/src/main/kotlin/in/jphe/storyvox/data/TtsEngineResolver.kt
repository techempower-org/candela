package `in`.jphe.storyvox.data

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech

/**
 * #1384 — picks a publicly bindable TTS engine so we never construct a
 * framework [TextToSpeech] with a null target.
 *
 * On Samsung the device-default TTS is a *private* engine. A null-target
 * `TextToSpeech(context)` asks the framework to bind that default; the
 * bind is refused ("not allowed to bind to private engine") and the
 * framework's fallback-then-reconnect path spins a connect/disconnect
 * loop that never delivers a clean `onInit`. The instance is therefore
 * never torn down and the loop (plus its CPU burn) runs forever — even
 * for users on Piper/Kokoro voices, because roster enumeration probes
 * System TTS at startup. Binding an explicit public engine skips the
 * private-default bind entirely.
 */
internal class TtsEngineResolver(private val context: Context) {

    /** Package ids of every installed engine that advertises the TTS
     *  service intent — the same set [TextToSpeech.getEngines] derives,
     *  obtained without first booting a [TextToSpeech]. */
    fun installedEnginePackages(): List<String> = runCatching {
        context.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    /** Engine to bind for default-style probing: never the (possibly
     *  private) device default — the highest-ranked public engine
     *  instead. Null only when no TTS engine is installed at all, in
     *  which case the caller skips enumeration rather than fall back to
     *  a null target. */
    fun preferredPublicEngine(): String? = pickPreferred(installedEnginePackages())

    /** #1390 — engine packages safe for per-engine probing in roster
     *  enumeration step 2. Excludes [SAMSUNG_PRIVATE_TTS]: Samsung's
     *  framework refuses the bind and enters a Handler-posted reconnect
     *  loop that [TextToSpeech.shutdown] cannot stop. */
    fun bindableEnginePackages(): List<String> =
        installedEnginePackages().filter { it !in PRIVATE_ENGINES }

    internal companion object {
        /** Google's TTS — the canonical public engine and the framework's
         *  own fallback when the device default can't be bound. */
        const val GOOGLE_TTS = "com.google.android.tts"

        /** Samsung's built-in TTS — marked "private" by Samsung's
         *  modified framework. Third-party bind attempts trigger
         *  an infinite connect/disconnect loop (#1384, #1390). */
        private const val SAMSUNG_PRIVATE_TTS = "com.samsung.SMT"

        private val PRIVATE_ENGINES = setOf(SAMSUNG_PRIVATE_TTS)

        /**
         * Choose which engine to bind from the installed set: Google
         * first, then a deterministic (sorted) pick so repeated probes
         * resolve to the same engine. Returns null only for an empty
         * set — the signal to skip enumeration entirely.
         */
        internal fun pickPreferred(packages: List<String>): String? = when {
            packages.isEmpty() -> null
            GOOGLE_TTS in packages -> GOOGLE_TTS
            else -> packages.sorted().first()
        }
    }
}
