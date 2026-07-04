package `in`.jphe.storyvox.feature.techempower.screener

import kotlinx.serialization.json.Json

/**
 * Issue #1517 — pure JSON → [ScreenerCorpus] decode. No Android, no IO: the
 * caller supplies the raw string (the ViewModel reads it from assets). Kept
 * pure so the parse contract is covered by plain-JVM unit tests.
 */
object ScreenerCorpusParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): ScreenerCorpus = json.decodeFromString(ScreenerCorpus.serializer(), raw)
}
