package `in`.jphe.storyvox.playback.transcribe

/**
 * Issue #1368 — pulls newly-**stable** words out of a streaming recognizer's
 * growing hypothesis so the [ForcedAligner] is fed one settled token at a
 * time, in spoken order.
 *
 * sherpa-onnx's [`com.k2fsa.sherpa.onnx.OnlineRecognizer`] accumulates text
 * across an utterance: each `getResult` call returns the whole best
 * hypothesis *so far* (e.g. `"the"`, then `"the quick"`, then
 * `"the quick brown"`), and the tail token can still be revised by the next
 * decode. After the recognizer endpoints we `reset` the stream and the text
 * starts over. This class turns that growing string into a stream of new
 * words:
 *
 * - [newWords] returns words that have appeared since the last call, **except
 *   the final token** — which is held back because the recognizer may still
 *   revise it on the next frame. Feeding the aligner a half-heard last word
 *   and then its correction would be two tokens for one spoken word.
 * - [flush] is called when the recognizer endpoints (utterance finalized):
 *   the held-back tail is now settled, so emit it and reset for the next
 *   utterance.
 *
 * Pure and deterministic — no Android, no recognizer types — so the
 * stable-word policy is unit-testable by feeding hypothesis strings. The
 * [MicCaptureProcessor] owns the recognizer and calls these with
 * `result.text`.
 */
class AsrWordExtractor {

    /** Count of words already returned from the current utterance. */
    private var emitted = 0

    /**
     * New stable words in the growing hypothesis [hypothesisText] since the
     * last call. Holds back the final (still-mutable) token; [flush] releases
     * it when the utterance ends. Returns an empty list when nothing new has
     * stabilized.
     */
    fun newWords(hypothesisText: String): List<String> {
        val words = splitWords(hypothesisText)
        // All but the last word are considered stable; the last may still be
        // revised by the recognizer on the next decode.
        val stableCount = (words.size - 1).coerceAtLeast(0)
        if (stableCount <= emitted) return emptyList()
        val fresh = words.subList(emitted, stableCount).toList()
        emitted = stableCount
        return fresh
    }

    /**
     * The recognizer endpointed [hypothesisText]: the whole utterance is now
     * final, so emit any words still held back (including the previously
     * unstable tail) and reset for the next utterance.
     */
    fun flush(hypothesisText: String): List<String> {
        val words = splitWords(hypothesisText)
        val tail = if (emitted < words.size) words.subList(emitted, words.size).toList() else emptyList()
        emitted = 0
        return tail
    }

    /** Drop any in-flight state (e.g. capture stopped). */
    fun reset() {
        emitted = 0
    }

    private companion object {
        private val WHITESPACE = Regex("\\s+")
        fun splitWords(text: String): List<String> =
            text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    }
}
