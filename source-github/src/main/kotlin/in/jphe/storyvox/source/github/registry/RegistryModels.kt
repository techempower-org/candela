package `in`.jphe.storyvox.source.github.registry

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level shape of `registry.json`, fetched from
 * `raw.githubusercontent.com/techempower-org/candela-registry/main/registry.json`
 * (path overrideable by [Registry] for tests).
 *
 * The registry is curated and hand-edited, so each entry carries the
 * display fields directly. Filling them from `getRepo` would cost 60+
 * API calls per session for no benefit — the curator already typed the
 * title once when adding the entry, and storyvox's only job is to
 * display it.
 *
 * Step-3c surfaces these as the Featured row at the top of Browse →
 * GitHub. Step-3d will enrich with manifest data when the user opens
 * a specific entry.
 */
@Serializable
internal data class RegistryDocument(
    @SerialName("version") val version: Int,
    @SerialName("fictions") val fictions: List<RegistryEntry> = emptyList(),
)

@Serializable
internal data class RegistryEntry(
    /** Stable id, e.g. `github:jphein/example-fiction`. */
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("author") val author: String,
    @SerialName("description") val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    /** Pinned/featured at the top of the Featured row. */
    @SerialName("featured") val featured: Boolean = false,
    /** ISO-8601 date the curator added this row. Used for "recently added" sorting. */
    @SerialName("added_at") val addedAt: String? = null,
    /** Optional status hint; falls back to ONGOING. */
    @SerialName("status") val status: String? = null,
    /** Optional rating (0..5) the curator can assign for hand-picks. */
    @SerialName("rating") val rating: Float? = null,
    /** Optional chapter count snapshot at curation time. */
    @SerialName("chapter_count") val chapterCount: Int? = null,
)

/**
 * Map a registry entry to the cross-source [FictionSummary] used by
 * the data layer. Status falls back to ONGOING when unparseable, since
 * a curator's typo shouldn't crash the Featured row.
 */
internal fun RegistryEntry.toSummary(): FictionSummary = FictionSummary(
    id = id,
    sourceId = SourceIds.GITHUB,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    tags = tags,
    status = parseStatus(status),
    chapterCount = chapterCount,
    rating = rating,
)

private fun parseStatus(raw: String?): FictionStatus = when (raw?.uppercase()) {
    "COMPLETED" -> FictionStatus.COMPLETED
    "HIATUS" -> FictionStatus.HIATUS
    "DROPPED" -> FictionStatus.DROPPED
    else -> FictionStatus.ONGOING
}
