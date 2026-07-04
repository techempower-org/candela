package `in`.jphe.storyvox.feature.techempower.decoder

import kotlinx.serialization.json.Json

/**
 * Issue #1516 — pure JSON → [ExplainerCorpus] decode. No Android, no IO.
 */
object ExplainerCorpusParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): ExplainerCorpus =
        json.decodeFromString(ExplainerCorpus.serializer(), raw)
}
