package `in`.jphe.storyvox.feature.techempower.learnpaths

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Issue #1464 — data model for guided digital-literacy **learning paths**.
 *
 * A path is an ordered list of TechEMPOWER Guides (the anonymous-mode Notion
 * `notion:guides` fiction). Paths are data, not UI: the bundled
 * `assets/techempower/learning_paths.json` defines them, and adding a path is
 * a JSON append. Each step names a guide by its compact 32-hex Notion page id;
 * [chapterIdFor] derives the chapter id with the same `<fictionId>::<pageId>`
 * scheme `source-notion`'s `chapterIdFor(fictionId, pageId)` uses, so progress
 * reuses the existing chapter-played state (`userMarkedRead`) — no new table.
 */
@Serializable
data class LearningPathsCorpus(
    val schemaVersion: Int = 1,
    /** The Guides fiction every step's chapter belongs to. */
    val fictionId: String = DEFAULT_FICTION_ID,
    val note: String? = null,
    val paths: List<LearningPath> = emptyList(),
) {
    /** Chapter id for [step], matching `source-notion`'s anonymous-mode ids. */
    fun chapterIdFor(step: PathStep): String = "$fictionId::${step.pageId.replace("-", "")}"

    companion object {
        const val DEFAULT_FICTION_ID = "notion:guides"
    }
}

/** A bilingual string; [get] picks the language, falling back to EN. */
@Serializable
data class Localized(
    val en: String,
    val es: String? = null,
) {
    fun get(spanish: Boolean): String = if (spanish) (es ?: en) else en
}

@Serializable
data class LearningPath(
    val id: String,
    val title: Localized,
    val summary: Localized? = null,
    val steps: List<PathStep> = emptyList(),
)

@Serializable
data class PathStep(
    /** Compact (or hyphenated) 32-hex Notion page id of the guide. */
    val pageId: String,
    val title: Localized,
    /** One-line "why this step" shown under the title. */
    val why: Localized? = null,
)

/** Issue #1464 — pure JSON → [LearningPathsCorpus] decode. No Android, no IO. */
object LearningPathsParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): LearningPathsCorpus =
        json.decodeFromString(LearningPathsCorpus.serializer(), raw)
}
