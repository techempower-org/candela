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

    /** Encode [report] as pretty, deterministic JSON. */
    fun encode(report: ImpactReport): String = buildString {
        append("{\n")
        appendLine("  ${quote("schema")}: ${report.schema},")
        appendLine("  ${quote("period")}: ${quote(report.period)},")
        appendLine("  ${quote("app_version")}: ${quote(report.appVersion)},")
        appendLine("  ${quote("hours_listened_bucket")}: ${report.hoursListenedBucket},")
        appendLine("  ${quote("chapters_completed_bucket")}: ${report.chaptersCompletedBucket},")
        appendLine("  ${quote("books_completed_bucket")}: ${report.booksCompletedBucket},")
        appendLine("  ${quote("sources_used")}: ${encodeStringArray(report.sourcesUsed)}")
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
