package `in`.jphe.storyvox.data

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Legacy voice surface for SettingsViewModel + the legacy VoicePickerScreen +
 * ReaderViewModel. v0.4.0 introduced [in.jphe.storyvox.playback.voice.VoiceManager]
 * as the canonical source for voice install/select/download — those flows go
 * through it directly. This impl backs the framework-TTS-based "list voices the
 * OS knows about + preview them" affordance only.
 */
@Singleton
class VoiceProviderUiImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceProviderUi {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineResolver = TtsEngineResolver(context)

    override val installedVoices: Flow<List<UiVoice>> = flow {
        val tts = bootTts() ?: run {
            emit(emptyList())
            return@flow
        }
        // try/finally so a WhileSubscribed cancellation between boot and
        // shutdown can't leak the instance into a reconnect loop (#1384).
        val mapped = try {
            val voices = runCatching { tts.voices?.toList().orEmpty() }.getOrDefault(emptyList())
            voices
                .map {
                    UiVoice(
                        id = it.name,
                        label = humanize(it.name),
                        engine = "System TTS",
                        locale = it.locale.toLanguageTag(),
                    )
                }
                .sortedWith(compareBy({ it.locale }, { it.label }))
        } finally {
            runCatching { tts.shutdown() }
        }
        emit(mapped)
    }.shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override fun previewVoice(voice: UiVoice) {
        scope.launch {
            val tts = bootTts() ?: return@launch
            try {
                runCatching {
                    tts.voices?.firstOrNull { it.name == voice.id }?.let { tts.voice = it }
                    tts.speak(PREVIEW_TEXT, TextToSpeech.QUEUE_FLUSH, null, "preview-${voice.id}")
                }
                // Let the utterance play, then release.
                kotlinx.coroutines.delay(4_000L)
            } finally {
                runCatching { tts.shutdown() }
            }
        }
    }

    /**
     * Boot a one-shot Android [TextToSpeech] bound to an explicit public
     * engine. #1384 — a null target asks the framework to bind the device
     * default, which on Samsung is a private engine whose refused bind
     * spins a connect/disconnect loop that never fires onInit. The await
     * is timeout-bounded and cancellation tears the instance down so a
     * stuck init can't leak the instance into that loop.
     */
    private suspend fun bootTts(): TextToSpeech? = withTimeoutOrNull(INIT_TIMEOUT_MS) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            var tts: TextToSpeech? = null
            val onInit = TextToSpeech.OnInitListener { status ->
                if (cont.isCompleted) return@OnInitListener
                if (status == TextToSpeech.SUCCESS) {
                    cont.resume(tts) { runCatching { tts?.shutdown() } }
                } else {
                    runCatching { tts?.shutdown() }
                    cont.resume(null) {}
                }
            }
            val engine = engineResolver.preferredPublicEngine()
            tts = if (engine.isNullOrBlank()) {
                TextToSpeech(context, onInit)
            } else {
                TextToSpeech(context, onInit, engine)
            }
            cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
        }
    }

    private fun humanize(name: String): String {
        val cleaned = name.replace('_', '-').split('-').filter { it.isNotBlank() }
        return cleaned.lastOrNull()?.replaceFirstChar { it.titlecase() } ?: name
    }

    private companion object {
        const val PREVIEW_TEXT = "The brass lantern flickers. Welcome back to the Library Nocturne."

        /** #1384 — ceiling on the onInit await so a stuck engine init
         *  can't suspend (and leak) the instance forever. */
        const val INIT_TIMEOUT_MS: Long = 8_000
    }
}
