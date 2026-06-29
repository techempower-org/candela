package `in`.jphe.storyvox.data.dictionary

/**
 * Issue #1230 — turn the raw token under a reader long-press into a clean
 * dictionary lemma, and enumerate the case variants worth querying.
 *
 * The reader resolves the pressed glyph to a word via
 * `TextLayoutResult.getWordBoundary`, which is generous: it can hand back the
 * word with an attached quote, a trailing comma/period, a possessive `'s`, or
 * (sentence-initial) a leading capital. Wiktionary page titles, by contrast,
 * are exact — `dog`, not `"Dog,`. This file does the squaring-up, and is pure
 * so it unit-tests without a Compose/Android runtime (the same split the rest
 * of the reader's logic — `normalizeSelection`, `findMatches` — already uses).
 */

/**
 * Edge punctuation, quotes and stray digits the word-boundary gesture can drag
 * in. Kept to the *edges*: an internal hyphen (`mother-in-law`) or apostrophe
 * (`don't`) is part of the headword and must survive.
 */
private val EDGE_TRASH = Regex("""^[^\p{L}]+|[^\p{L}]+$""")

/** A trailing English possessive — `dog's` / `dogs'` → `dog` / `dogs`. Wiktionary
 *  lemmatises the base form, so the possessive clitic is stripped before lookup.
 *  Both the straight and typographic apostrophe are handled. */
private val TRAILING_POSSESSIVE = Regex("""['’]s?$""")

/**
 * Normalise [raw] into a dictionary lemma, or null when there's nothing
 * look-up-able left (empty, all-punctuation, or a bare number).
 *
 * Steps, in order:
 *  1. trim surrounding whitespace,
 *  2. strip leading/trailing non-letters (quotes, commas, ellipses, digits),
 *  3. strip a trailing possessive clitic (`'s` / `'`),
 *  4. re-strip any edge punctuation the clitic removal exposed,
 *  5. reject the result if it now contains no letter at all.
 *
 * Case is **preserved** — `Serendipity` stays capitalised here so a genuine
 * proper noun keeps its form; [lemmaCandidates] handles the de-capitalisation
 * fallback for ordinary sentence-initial words.
 */
fun normalizeLookupWord(raw: String): String? {
    if (raw.isBlank()) return null
    var word = raw.trim()
    word = EDGE_TRASH.replace(word, "")
    word = TRAILING_POSSESSIVE.replace(word, "")
    word = EDGE_TRASH.replace(word, "")
    if (word.isEmpty()) return null
    if (word.none { it.isLetter() }) return null
    return word
}

/**
 * The lemma forms to try, in priority order, for a [word] already run through
 * [normalizeLookupWord]. Wiktionary entry titles are case-sensitive: common
 * words are lower-case (`the`, `serendipity`) while proper nouns keep their
 * capital (`London`). A word lifted from mid-sentence is usually already in its
 * dictionary case; a sentence-initial word is capitalised only by position.
 *
 * So we query the word as-seen first (correct for proper nouns and mid-sentence
 * words), then — only if it differs — a de-capitalised variant that lower-cases
 * just the first letter (`The` → `the`, `Serendipity` → `serendipity`). An
 * all-caps acronym additionally gets a fully lower-cased try. Duplicates are
 * removed so the implementation never makes a redundant request.
 *
 * Returns an empty list for a blank input (the caller should have rejected it
 * via [normalizeLookupWord] first).
 */
fun lemmaCandidates(word: String): List<String> {
    if (word.isEmpty()) return emptyList()
    val candidates = LinkedHashSet<String>()
    candidates += word
    // #1265 — an all-caps acronym ("NASA") skips the first-letter-decapitalise
    // step: lowercasing only its first letter yields a nonsense mixed-case form
    // ("nASA") that's a guaranteed Wiktionary 404 — a wasted sequential request
    // on mobile. The fully-lower-cased form below ("nasa") still covers it.
    val isAcronym = word == word.uppercase() && word != word.lowercase()
    // De-capitalise the first letter only (the common sentence-initial case).
    if (word.first().isUpperCase() && !isAcronym) {
        candidates += word.replaceFirstChar { it.lowercaseChar() }
    }
    // A SHOUTED or Mixed-case token: a fully lower-cased form is worth a try.
    val lower = word.lowercase()
    candidates += lower
    return candidates.toList()
}
