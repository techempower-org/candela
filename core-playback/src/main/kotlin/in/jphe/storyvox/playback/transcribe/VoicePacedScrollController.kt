package `in`.jphe.storyvox.playback.transcribe

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Issue #1368 — the stateful bridge that ties the live word stream to the
 * pure alignment logic for the voice-paced teleprompter. It is the one place
 * that holds session state across the otherwise-pure pieces:
 *
 * ```
 * RecognizedWordSource.words ─▶ ForcedAligner.onWord ─▶ positionChar (StateFlow)
 * ```
 *
 * [start] builds a fresh [ForcedAligner] for the chapter text and begins
 * collecting words from the injected [RecognizedWordSource] (the live
 * [MicCaptureProcessor] in production, or a fake in tests); each recognized
 * word advances the aligner and publishes the new character offset on
 * [positionChar]. [stop] cancels the collection and stops capture.
 *
 * ### Why this exposes a *character* offset, not a scroll pixel
 * The brief imagined a `scrollTarget` pixel flow here, but the pure
 * [VoicePacedScroller] turns a char offset into a pixel target using the
 * **viewport geometry**, which is surface-specific (the phone reader and a
 * future Wear face have different viewports) and lives in Compose. So this
 * @Singleton stays surface-agnostic — it publishes *where in the text the
 * speaker is* — and the reader owns a [VoicePacedScroller] that maps
 * [positionChar] → scroll px against its own measured layout. Clean seam: no
 * Compose / no viewport metrics leak into core-playback.
 */
@Singleton
class VoicePacedScrollController @Inject constructor(
    private val wordSource: RecognizedWordSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _positionChar = MutableStateFlow(0)

    /** Character offset of where the speaker currently is in the chapter
     *  (start of the last matched word); 0 before the first match. */
    val positionChar: StateFlow<Int> = _positionChar.asStateFlow()

    private val _listening = MutableStateFlow(false)

    /** True between [start] and [stop] — the mic session is active. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /**
     * Begin a voice-paced session over [chapterText]: build the aligner, start
     * capture, and stream recognized words into it. Idempotent while already
     * running (a no-op until [stop]).
     */
    fun start(chapterText: String) {
        if (job?.isActive == true) return
        val aligner = ForcedAligner(chapterText)
        _positionChar.value = 0
        _listening.value = true
        wordSource.start()
        job = scope.launch {
            wordSource.words.collect { word ->
                _positionChar.value = aligner.onWord(word.text, word.confidence)
            }
        }
    }

    /** End the session: stop capture and the word collection. Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
        wordSource.stop()
        _listening.value = false
    }
}
