package `in`.jphe.storyvox.data.repository

/**
 * Issue #1189 — derive a short, human-readable preview snippet from a
 * chapter's cached body text.
 *
 * Why this exists: when a source numbers its chapters generically
 * ("Chapter 1", "Chapter 2", …) the table-of-contents on FictionDetail
 * gives the listener nothing to orient by. A ~100-char taste of the
 * chapter's opening prose lets them find their place at a glance.
 *
 * Input is `Chapter.plainBody` (the source's plaintext rendering). That
 * column is documented as possibly being a *naive* strip of the HTML
 * body, so it can still carry residual tags, HTML entities and runaway
 * whitespace. This cleaner is therefore defensive rather than trusting:
 *
 *  1. strip any residual `<…>` tags,
 *  2. decode the handful of HTML entities that survive a naive strip,
 *  3. collapse every whitespace run (newlines, tabs, doubled spaces) to
 *     a single space and trim,
 *  4. drop a leading "Chapter N" / "Ch. N -" style prefix — bodies that
 *     open by repeating their own (often generic) title would otherwise
 *     preview the exact text the snippet is meant to disambiguate
 *     (mirrors the intent of core-ui's `stripChapterPrefix`),
 *  5. truncate to [maxChars] on a word boundary, appending an ellipsis
 *     when the body was longer.
 *
 * Pure Kotlin (no `android.text.Html`) so it unit-tests under the
 * project's JUnit-only / no-Robolectric harness. Returns `null` when
 * there's nothing meaningful left to show, so the UI can omit the line
 * entirely rather than render an empty row.
 */
fun chapterPreviewText(raw: String?, maxChars: Int = PREVIEW_MAX_CHARS): String? {
    if (raw.isNullOrBlank()) return null

    val noTags = HTML_TAG.replace(raw, " ")
    val decoded = decodeBasicEntities(noTags)
    val collapsed = WHITESPACE.replace(decoded, " ").trim()
    val deTitled = LEADING_CHAPTER_PREFIX.replaceFirst(collapsed, "").trim()
    // Falling back to the collapsed text guards the pathological case where a
    // chapter's whole body is just its "Chapter N" heading — stripping it
    // would leave nothing, so we'd rather show the heading than an empty line.
    val text = deTitled.ifBlank { collapsed }
    if (text.isBlank()) return null

    return truncateOnWordBoundary(text, maxChars)
}

/** Default snippet length — roughly one line of secondary text on a phone. */
const val PREVIEW_MAX_CHARS: Int = 100

private val HTML_TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")

/** Same shapes core-ui's `stripChapterPrefix` collapses, plus a bare
 *  "Chapter N" with no separator (a body opening on its own heading). */
private val LEADING_CHAPTER_PREFIX =
    Regex("^(?:ch(?:apter)?\\.?)\\s*\\d+\\s*[-:—–.]?\\s*", RegexOption.IGNORE_CASE)

/** Numeric character references: `&#1234;` (decimal) and `&#x1F600;` (hex). */
private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")

/**
 * Decode the small set of HTML entities that routinely survive a source's
 * naive plaintext strip. Not a full entity table — just the ones that show
 * up in real prose (ampersands, quotes, dashes, non-breaking spaces) plus
 * numeric references. Anything unrecognised is left untouched.
 */
private fun decodeBasicEntities(s: String): String {
    if ('&' !in s) return s
    var out = s
    NAMED_ENTITIES.forEach { (entity, replacement) -> out = out.replace(entity, replacement) }
    out = NUMERIC_ENTITY.replace(out) { m ->
        val isHex = m.groupValues[1] == "x"
        val code = m.groupValues[2].toIntOrNull(if (isHex) 16 else 10)
        if (code != null && code in 1..0x10FFFF) {
            runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
        } else {
            m.value
        }
    }
    return out
}

private val NAMED_ENTITIES = listOf(
    "&nbsp;" to " ",
    "&amp;" to "&",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&hellip;" to "…",
    "&mdash;" to "—",
    "&ndash;" to "–",
    "&rsquo;" to "’",
    "&lsquo;" to "‘",
    "&rdquo;" to "”",
    "&ldquo;" to "“",
)

/**
 * Truncate to at most [maxChars], preferring the last word boundary so we
 * don't slice a word in half. Appends a single-character ellipsis when the
 * input was actually longer than the limit. A snippet at or under the limit
 * is returned verbatim (no ellipsis).
 */
private fun truncateOnWordBoundary(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val window = text.take(maxChars)
    val lastSpace = window.lastIndexOf(' ')
    // Only honour the word boundary when it isn't pathologically early
    // (a single very long token shouldn't collapse the snippet to nothing).
    val cut = if (lastSpace >= maxChars / 2) window.substring(0, lastSpace) else window
    return cut.trimEnd().trimEnd('.', ',', ';', ':', '—', '–', '-') + "…"
}
