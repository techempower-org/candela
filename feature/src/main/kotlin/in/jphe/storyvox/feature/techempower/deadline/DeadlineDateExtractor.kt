package `in`.jphe.storyvox.feature.techempower.deadline

import java.time.LocalDate

/**
 * Issue #1515 — pure, on-device date extraction over OCR'd notice text.
 *
 * Given the recognized text of a benefits letter, surface the calendar
 * dates a human might act on ("please respond by August 31, 2026",
 * "renueve antes del 15 de septiembre de 2026", "Due: 09/30/2026"),
 * ranked so the most-likely deadline floats to the top. The user always
 * confirms — we never auto-schedule a guess.
 *
 * Deliberately regex + [java.time] only: no ML, no network, no Android
 * types. That keeps it fully JVM-unit-testable (see
 * `DeadlineDateExtractorTest`) and honours the "on-device / airplane
 * mode" invariant — the whole extraction path is offline arithmetic over
 * a string.
 *
 * Bilingual by construction: English AND Spanish month names + deadline
 * cue phrases are first-class (EN/ES parity invariant), because a
 * Spanish-language CalFresh or Medi-Cal notice is exactly the audience.
 */
object DeadlineDateExtractor {

    /** Cap the candidate list so a text-dense notice doesn't overwhelm the picker. */
    private const val MAX_CANDIDATES = 8

    /** How far before / after a date to look for a deadline cue phrase. */
    private const val CUE_LOOKBEHIND = 45
    private const val CUE_LOOKAHEAD = 30

    /**
     * Month names → month number (1..12). English + Spanish, full names
     * and common abbreviations. Lower-cased, accent-free lookups; the
     * caller strips a trailing period ("Aug." → "aug") and accents
     * ("septiembre") before looking up.
     */
    private val MONTHS: Map<String, Int> = buildMap {
        // English
        putMonth(1, "january", "jan")
        putMonth(2, "february", "feb")
        putMonth(3, "march", "mar")
        putMonth(4, "april", "apr")
        putMonth(5, "may")
        putMonth(6, "june", "jun")
        putMonth(7, "july", "jul")
        putMonth(8, "august", "aug")
        putMonth(9, "september", "sep", "sept")
        putMonth(10, "october", "oct")
        putMonth(11, "november", "nov")
        putMonth(12, "december", "dec")
        // Spanish (accents already stripped by [normalizeWord])
        putMonth(1, "enero", "ene")
        putMonth(2, "febrero")
        putMonth(3, "marzo")
        putMonth(4, "abril", "abr")
        putMonth(5, "mayo")
        putMonth(6, "junio")
        putMonth(7, "julio")
        putMonth(8, "agosto", "ago")
        putMonth(9, "septiembre", "setiembre", "set")
        putMonth(10, "octubre")
        putMonth(11, "noviembre")
        putMonth(12, "diciembre", "dic")
    }

    private fun MutableMap<String, Int>.putMonth(number: Int, vararg names: String) {
        names.forEach { put(it, number) }
    }

    /**
     * Deadline cue phrases, longest / most-specific first so the nearest
     * *and* most-specific phrase wins. Matched case-insensitively against
     * accent-free text.
     */
    private val CUES: List<String> = listOf(
        // English
        "must be received by", "no later than", "response due", "respond by",
        "reply by", "renew before", "renew by", "renewal", "apply by",
        "return by", "submit by", "received by", "expires on", "expires",
        "expire by", "deadline", "due by", "due on", "due", "before",
        // Spanish (accent-free)
        "responder antes del", "responder antes de", "renovar antes del",
        "renovar antes de", "presentar antes del", "presentar antes de",
        "envie antes del", "envie antes de", "fecha limite", "vencimiento",
        "vence el", "vence", "antes del", "antes de", "limite",
    )

    // Day-first: "31 August 2026", "31 de agosto de 2026", "31 de agosto del 2026".
    private val DAY_MONTH_YEAR = Regex(
        """(\d{1,2})\s+(?:de\s+)?([\p{L}.]{3,12})(?:\s+del?)?\s*,?\s*(\d{4})""",
        RegexOption.IGNORE_CASE,
    )

    // Month-first: "August 31, 2026", "agosto 31 de 2026", "Aug 31st 2026".
    private val MONTH_DAY_YEAR = Regex(
        """([\p{L}.]{3,12})\s+(\d{1,2})(?:st|nd|rd|th)?\s*,?\s*(?:del?\s+)?(\d{4})""",
        RegexOption.IGNORE_CASE,
    )

    // US numeric: "08/31/2026", "8-31-26". Month-first (US notices).
    // Only "/" and "-" separators — "." invites version-number / decimal
    // false positives and US notices don't use it for dates.
    private val NUMERIC_US = Regex("""\b(\d{1,2})[/\-](\d{1,2})[/\-](\d{2,4})\b""")

    // ISO: "2026-08-31".
    private val NUMERIC_ISO = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")

    /**
     * Parse [text] and return the deadline candidates, best-first.
     *
     * Ranking: future-or-today dates first, then dates that carry a
     * deadline cue, then nearest date first. De-duplicated by calendar
     * date (a date mentioned twice, or matched by two patterns, appears
     * once — preferring the mention that had a cue).
     *
     * @param today the reference date used to flag past dates; injectable
     *   so tests are deterministic (no wall-clock read).
     */
    fun extract(text: String, today: LocalDate): List<DateCandidate> {
        if (text.isBlank()) return emptyList()
        val accentFree = stripAccents(text)

        val raw = buildList {
            addAll(matchMonthName(text, accentFree, MONTH_DAY_YEAR, monthFirst = true))
            addAll(matchMonthName(text, accentFree, DAY_MONTH_YEAR, monthFirst = false))
            addAll(matchNumericIso(text))
            addAll(matchNumericUs(text))
        }

        // De-dup by date; keep the richest mention (one with a cue wins,
        // else the first seen).
        val byDate = LinkedHashMap<LocalDate, DateCandidate>()
        for (c in raw) {
            val existing = byDate[c.date]
            if (existing == null || (existing.cue == null && c.cue != null)) {
                byDate[c.date] = c
            }
        }

        return byDate.values
            .map { it.copy(isPast = it.date.isBefore(today)) }
            .sortedWith(
                compareBy(
                    { it.isPast },                 // future/today first
                    { it.cue == null },            // cued dates first
                    { it.date },                   // nearest deadline first
                ),
            )
            .take(MAX_CANDIDATES)
    }

    private fun matchMonthName(
        original: String,
        accentFree: String,
        pattern: Regex,
        monthFirst: Boolean,
    ): List<DateCandidate> = pattern.findAll(accentFree).mapNotNull { m ->
        val (wordGroup, dayGroup, yearGroup) = if (monthFirst) {
            Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        } else {
            Triple(m.groupValues[2], m.groupValues[1], m.groupValues[3])
        }
        val month = MONTHS[normalizeWord(wordGroup)] ?: return@mapNotNull null
        val date = safeDate(yearGroup.toInt(), month, dayGroup.toIntOrNull() ?: return@mapNotNull null)
            ?: return@mapNotNull null
        candidateAt(original, accentFree, m.range.first, m.range.last, date)
    }.toList()

    private fun matchNumericUs(text: String): List<DateCandidate> {
        val accentFree = text // numeric-only, accents irrelevant
        return NUMERIC_US.findAll(text).mapNotNull { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            val date = safeDate(expandYear(m.groupValues[3]), month, day) ?: return@mapNotNull null
            candidateAt(text, accentFree, m.range.first, m.range.last, date)
        }.toList()
    }

    private fun matchNumericIso(text: String): List<DateCandidate> =
        NUMERIC_ISO.findAll(text).mapNotNull { m ->
            val date = safeDate(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
            ) ?: return@mapNotNull null
            candidateAt(text, text, m.range.first, m.range.last, date)
        }.toList()

    /** Build a [DateCandidate], pulling its snippet + nearest cue from around the match. */
    private fun candidateAt(
        original: String,
        accentFree: String,
        start: Int,
        endInclusive: Int,
        date: LocalDate,
    ): DateCandidate {
        val end = endInclusive + 1
        val rawText = original.substring(start, end).trim()
        return DateCandidate(
            date = date,
            rawText = rawText,
            snippet = snippetAround(original, start, end),
            cue = nearestCue(accentFree, start, end),
            isPast = false, // set during ranking in extract()
        )
    }

    /** The trimmed, whitespace-collapsed line containing the match, capped for display. */
    private fun snippetAround(text: String, start: Int, end: Int): String {
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val nl = text.indexOf('\n', end)
        val lineEnd = if (nl < 0) text.length else nl
        val line = text.substring(lineStart, lineEnd).replace(Regex("""\s+"""), " ").trim()
        return if (line.length > 100) line.take(99).trimEnd() + "…" else line
    }

    /** Closest cue phrase within the look-behind / look-ahead window, or null. */
    private fun nearestCue(accentFree: String, start: Int, end: Int): String? {
        val windowStart = (start - CUE_LOOKBEHIND).coerceAtLeast(0)
        val windowEnd = (end + CUE_LOOKAHEAD).coerceAtMost(accentFree.length)
        val window = accentFree.substring(windowStart, windowEnd).lowercase()
        // Position of the date within the window, to measure distance.
        val datePos = start - windowStart
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (cue in CUES) {
            val idx = window.indexOf(cue)
            if (idx >= 0) {
                val distance = kotlin.math.abs(idx - datePos)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = cue
                }
            }
        }
        return best
    }

    /** 2-digit years → 2000s; 4-digit passed through. */
    private fun expandYear(raw: String): Int {
        val n = raw.toInt()
        return if (raw.length <= 2) 2000 + n else n
    }

    /** Validate + build a LocalDate, returning null for impossible values (month 13, Feb 30, …). */
    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? {
        if (month !in 1..12 || day !in 1..31 || year < 1900 || year > 2200) return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun normalizeWord(word: String): String =
        word.trimEnd('.').lowercase()

    /**
     * Strip diacritics so "agosto"/"días" match accent-free keys/cues.
     *
     * Length-preserving on purpose: match ranges from the accent-free
     * text are used to slice the ORIGINAL text (for [DateCandidate.rawText]
     * / snippet), so both strings must stay index-aligned. A whole-string
     * NFD-then-remove-marks pass would change the length (é → e + mark)
     * and desync the indices, so we normalise per character and keep only
     * the base letter — exactly one output char per input char.
     */
    private fun stripAccents(s: String): String = buildString(s.length) {
        for (ch in s) {
            val base = java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFD)[0]
            append(base)
        }
    }
}
