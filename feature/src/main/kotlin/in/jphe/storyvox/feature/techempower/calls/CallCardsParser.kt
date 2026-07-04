package `in`.jphe.storyvox.feature.techempower.calls

import kotlinx.serialization.json.Json

/** Issue #1518 — pure JSON → [CallCardsCorpus] decode. No Android, no IO. */
object CallCardsParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): CallCardsCorpus =
        json.decodeFromString(CallCardsCorpus.serializer(), raw)
}
