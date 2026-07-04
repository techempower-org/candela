package `in`.jphe.storyvox.feature.techempower.decoder

/**
 * Issue #1516 — pure form-number detection over OCR'd (or typed) notice text.
 *
 * CA notices carry a form number in a footer or title line — e.g. `NA 200`,
 * `NA 274`, `MC 355`, `SAR 7`. These are template documents, so a regex for
 * "2–4 letters + 1–4 digits" reliably surfaces candidate form numbers. We do
 * NOT interpret the letter's contents; we only find the form-number token and
 * hand it to the corpus lookup. Detection producing a candidate the corpus does
 * not know → honest "no verified explainer" fallback, never a guess.
 */
object NoticeFormDetector {

    // Prefix (2–4 letters), optional space/dash, then 1–4 digits.
    private val FORM_PATTERN = Regex("""\b([A-Za-z]{2,4})[ \-]?(\d{1,4})\b""")

    /**
     * All distinct normalized form-number candidates in [text], in first-seen
     * order. Empty when nothing looks like a form number.
     */
    fun detectCandidates(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (match in FORM_PATTERN.findAll(text)) {
            val prefix = match.groupValues[1]
            val digits = match.groupValues[2]
            seen.add(normalizeFormNumber("$prefix$digits"))
        }
        return seen.toList()
    }

    /**
     * The first candidate in [text] that [corpus] has a verified explainer for,
     * or null. This is the "did we recognize this notice?" answer.
     */
    fun firstKnown(text: String, corpus: ExplainerCorpus): NoticeExplainer? {
        for (candidate in detectCandidates(text)) {
            corpus.find(candidate)?.let { return it }
        }
        return null
    }
}
