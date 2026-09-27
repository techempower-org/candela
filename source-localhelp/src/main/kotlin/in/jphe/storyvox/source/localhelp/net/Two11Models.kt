package `in`.jphe.storyvox.source.localhelp.net

import kotlinx.serialization.Serializable

/**
 * Issue #1465 — the `DocumentSearchResult[Resource]` envelope returned by the
 * 211 NDP `Search/Keyword` operation (schema from the public API portal).
 * Every field is nullable/defaulted: 211 records are contributed by ~200
 * independent centres and sparse records are the norm, not the exception.
 */
@Serializable
internal data class Two11SearchResponse(
    val count: Long? = null,
    val results: List<Two11SearchResult> = emptyList(),
)

@Serializable
internal data class Two11SearchResult(
    val score: Double? = null,
    val document: Two11Resource? = null,
)

/** One service-at-location hit — the unit Candela narrates as a "fiction". */
@Serializable
internal data class Two11Resource(
    val id: String? = null,
    val idServiceAtLocation: String? = null,
    val idService: String? = null,
    val idOrganization: String? = null,
    val idLocation: String? = null,
    val nameService: String? = null,
    val descriptionService: String? = null,
    val nameOrganization: String? = null,
    val descriptionOrganization: String? = null,
    val nameLocation: String? = null,
    val address1PhysicalAddress: String? = null,
    val cityPhysicalAddress: String? = null,
    val regionPhysicalAddress: String? = null,
    val statePhysicalAddress: String? = null,
    val taxonomyTerm: List<String> = emptyList(),
    val topicTaxonomy: List<String> = emptyList(),
    val subtopicTaxonomy: List<String> = emptyList(),
    val dataOwner: String? = null,
    val serviceArea: List<String> = emptyList(),
)
