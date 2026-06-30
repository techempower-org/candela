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
 * [start] strips the script's non-spoken cues + banners ([ScriptCueFilter]),
 * builds a fresh [ForcedAligner] over the spoken text, and begins collecting
 * words from the injected [RecognizedWordSource] (the live [MicCaptureProcessor]
 * in production, or a fake in tests); each recognized word advances the aligner.
 * The aligner's offset (in *stripped* space) is mapped back to the **source**
 * text so [positionChar] is an offset the reader can scroll against directly.
 * [stop] cancels the collection and stops capture.
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

    private val _wristLines = MutableStateFlow(WristLines.EMPTY)

    /**
     * The current + next display line the speaker is on, derived from
     * [positionChar]. Surface-agnostic *text* (not pixels) — the phone ships it
     * to the Wear teleprompter remote so the watch can render the line without
     * holding the whole chapter. Empty between sessions; a [StateFlow] dedups,
     * so it only changes when the speaker crosses into a new line, not on every
     * recognized word.
     */
    val wristLines: StateFlow<WristLines> = _wristLines.asStateFlow()

    /** Display-line spans of the current chapter (original text, so spans line
     *  up with the source-space [positionChar]). Rebuilt each [start]. */
    private var lineSpans: List<LineSpan> = emptyList()

    /**
     * Begin a voice-paced session over [chapterText]: build the aligner, start
     * capture, and stream recognized words into it. Idempotent while already
     * running (a no-op until [stop]).
     */
    fun start(chapterText: String) {
        if (job?.isActive == true) return
        // Strip cues/banners so the aligner only matches spoken words, but keep
        // the map back to source offsets — the reader scrolls against the
        // displayed (un-stripped) chapter text.
        val spoken = ScriptCueFilter.spokenText(chapterText)
        val aligner = ForcedAligner(spoken.text)
        // Display lines are split from the ORIGINAL text so their spans align
        // with the source-space offset published below.
        lineSpans = splitTeleprompterLines(chapterText)
        _positionChar.value = 0
        _wristLines.value = wristLinesAt(lineSpans, 0)
        _listening.value = true
        wordSource.start()
        job = scope.launch {
            wordSource.words.collect { word ->
                val spokenOffset = aligner.onWord(word.text, word.confidence)
                val sourceOffset = spoken.toSourceOffset(spokenOffset)
                _positionChar.value = sourceOffset
                _wristLines.value = wristLinesAt(lineSpans, sourceOffset)
            }
        }
    }

    /** End the session: stop capture and the word collection. Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
        wordSource.stop()
        _listening.value = false
        _wristLines.value = WristLines.EMPTY
    }
}
