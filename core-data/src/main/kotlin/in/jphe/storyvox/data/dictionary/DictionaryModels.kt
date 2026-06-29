package `in`.jphe.storyvox.data.dictionary

/**
 * Issue #1230 — tap-to-define dictionary in the reader. The model layer the
 * reader's long-press popup renders, and the contract its repository fulfils.
 *
 * The shapes are deliberately source-agnostic: today the only implementation
 * is [DictionaryRepository] over the Wiktionary REST API, but a [WordDefinition]
 * carries nothing Wiktionary-specific, so an offline cache or an alternate
 * dictionary backend can satisfy the same contract without touching the UI.
 *
 * Pure Kotlin (no Android, no OkHttp) so the parser + normaliser that produce
 * these can be unit-tested under the project's JUnit-only / no-Robolectric
 * harness — the okhttp-backed implementation lives in `:app` (the only module
 * that carries the descriptive User-Agent Wikimedia's policy requires).
 */

/**
 * One part-of-speech grouping for a word — e.g. *Noun* with its senses, then
 * *Verb* with its senses. Mirrors a single Wiktionary REST definition group.
 *
 * @property partOfSpeech display label ("Noun", "Verb", "Adjective", …). May be
 *   blank when the source omits it; the UI then renders the senses without a
 *   heading rather than an empty chip.
 * @property senses human-readable definition glosses, in source order, already
 *   stripped of the inline HTML the REST API embeds. Never empty — a group with
 *   no usable glosses is dropped by the parser rather than represented here.
 */
data class DictionaryEntry(
    val partOfSpeech: String,
    val senses: List<String>,
)

/**
 * A resolved dictionary entry for a single [word].
 *
 * @property word the lemma actually looked up (post-normalisation), so the UI
 *   shows the headword it found rather than the raw glyphs under the finger.
 * @property pronunciation IPA / respelling when the source provides it. The
 *   Wiktionary REST *definition* endpoint generally omits pronunciation, so this
 *   is usually null; the sheet shows the row only when it's present.
 * @property entries one [DictionaryEntry] per part of speech. Never empty — an
 *   empty result is surfaced as [DictionaryResult.NotFound], not a hollow
 *   [WordDefinition].
 */
data class WordDefinition(
    val word: String,
    val pronunciation: String?,
    val entries: List<DictionaryEntry>,
)

/**
 * The outcome of a [DictionaryRepository.define] call. Modelled as a sealed
 * result rather than a nullable + thrown exception so the reader can render a
 * distinct surface for each case — a real definition, a clean "no entry"
 * (offer the system-dictionary / Ask-AI fallbacks), or a transient network
 * error (offer Retry) — without try/catch in the UI layer.
 */
sealed interface DictionaryResult {
    /** A definition was found. */
    data class Success(val definition: WordDefinition) : DictionaryResult

    /** The lookup succeeded but the word has no entry (HTTP 404 or an empty
     *  body). [word] is the normalised lemma that was searched. */
    data class NotFound(val word: String) : DictionaryResult

    /** The lookup failed (no network, server error, malformed body). [message]
     *  is a short human-readable reason for the reader's error row. */
    data class Error(val word: String, val message: String) : DictionaryResult
}

/**
 * Looks up a word's definition. Implementations do the network / cache work on
 * an IO dispatcher; callers (the reader ViewModel) invoke from a coroutine and
 * map the [DictionaryResult] onto UI state.
 *
 * The interface lives in `:core-data` so the ViewModel can depend on it without
 * pulling OkHttp into the feature graph; the concrete Wiktionary implementation
 * is bound in `:app`.
 */
interface DictionaryRepository {
    /**
     * Resolve [word] (a raw token straight from the reader's word-boundary
     * gesture — normalisation is the implementation's job via
     * [normalizeLookupWord]). Never throws; failures come back as
     * [DictionaryResult.Error].
     */
    suspend fun define(word: String): DictionaryResult
}
