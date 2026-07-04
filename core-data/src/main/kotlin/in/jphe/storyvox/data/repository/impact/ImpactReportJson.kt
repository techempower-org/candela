package `in`.jphe.storyvox.data.repository.impact

/**
 * Issue #1463 — the canonical, **deterministic** text encoding of an [ImpactReport].
 *
 * This string is exactly what the user shares AND exactly what the preview sheet
 * renders (byte-for-byte) — the "you see precisely what will be shared" contract holds
 * because the preview and the share sheet both call [encode] on the same report.
 *
 * Hand-built (no JSON library) so the output is stable and unit-testable: fixed key
 * order matching the spec §4.1, 2-space indent, arrays inline. All values are known-safe
 * (integers, `YYYY-MM`, `major.minor`, built-in source IDs) but strings are still quoted
 * with minimal escaping so a stray character can never produce invalid JSON.
 */
object ImpactReportJson {

    /** Encode [report] as pretty, deterministic JSON.
     *
     *  #1525 — line breaks are written as an explicit `\n` (not `appendLine`)
     *  so the byte-for-byte determinism contract in the class KDoc is
     *  self-evident and can never regress to a platform line separator. */
    fun encode(report: ImpactReport): String = buildString {
        append("{\n")
        append("  ${quote("schema")}: ${report.schema},\n")
        append("  ${quote("period")}: ${quote(report.period)},\n")
        append("  ${quote("app_version")}: ${quote(report.appVersion)},\n")
        append("  ${quote("hours_listened_bucket")}: ${report.hoursListenedBucket},\n")
        append("  ${quote("chapters_completed_bucket")}: ${report.chaptersCompletedBucket},\n")
        append("  ${quote("books_completed_bucket")}: ${report.booksCompletedBucket},\n")
        append("  ${quote("sources_used")}: ${encodeStringArray(report.sourcesUsed)}\n")
        append("}")
    }

    private fun encodeStringArray(values: List<String>): String {
        if (values.isEmpty()) return "[]"
        return values.joinToString(prefix = "[", postfix = "]", separator = ", ") { quote(it) }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}
