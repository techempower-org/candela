package `in`.jphe.storyvox.source.notion.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Issue #393 — wire types for the **unofficial** Notion API surface at
 * `www.notion.so/api/v3`. Same set react-notion-x's `notion-client` hits
 * (we reverse-engineered the shapes by probing TechEmpower's public
 * Notion tree on 2026-05-13).
 *
 * Endpoints modelled:
 *
 *  - **POST `/api/v3/loadPageChunk`** — fetch a page's blocks as a
 *    "recordMap" (Notion's flat keyed-by-uuid store). Body:
 *    `{pageId, limit, chunkNumber, cursor:{stack:[]}, verticalColumns:false}`.
 *    Response wraps a [NotionRecordMap]; the page id key in
 *    `recordMap.block` holds the page block with a `content` array of
 *    child block ids in display order.
 *
 *  - **POST `/api/v3/queryCollection`** — query a database (collection)
 *    for rows. Body wraps `{collection:{id},collectionView:{id},source,loader}`
 *    where loader is the reducer-typed query shape react-notion-x uses.
 *    Response: `{result:{reducerResults:{collection_group_results:{blockIds:[...]}}}, recordMap}`.
 *    The recordMap contains the row pages by id.
 *
 *  - **POST `/api/v3/syncRecordValuesMain`** — fetch individual block
 *    records by id (used when a child page block isn't already in the
 *    parent's recordMap — e.g. when the user opens a deep link). Body:
 *    `{requests:[{id, table:"block", version:-1}]}`. Response: a
 *    [NotionRecordMap]. Note: the spec doc says `getRecordValues` but
 *    that endpoint returns 502 MemcachedCrossCellError; `syncRecordValuesMain`
 *    is the working successor.
 *
 *  - **POST `/api/v3/getPublicPageData`** — verify a page is publicly
 *    shared and resolve its space metadata. Body:
 *    `{type:"block-space",name:"page",blockId,saveParent:false,showMoveTo:false,showWebsiteLogin:true}`.
 *    Response: [NotionPublicPageData]. 200 with `publicAccessRole`
 *    means public; 4xx (or 200 with `requireLogin:true`) means gated.
 *
 * Notion's response payloads are large + recursive. We model the
 * structural keys (block envelopes, properties, content[]) and keep
 * everything below as `JsonElement` so unknown block types don't fail
 * decoding — same permissive posture as the official-API models.
 */

/** Top-level recordMap that every unofficial endpoint returns. */
@Serializable
internal data class NotionRecordMap(
    @SerialName("__version__") val version: Int = 0,
    val block: Map<String, NotionBlockEnvelope> = emptyMap(),
    val collection: Map<String, NotionCollectionEnvelope> = emptyMap(),
    @SerialName("collection_view") val collectionView: Map<String, JsonElement> = emptyMap(),
    @SerialName("notion_user") val notionUser: Map<String, JsonElement> = emptyMap(),
    val space: Map<String, JsonElement> = emptyMap(),
)

/** Envelope around every block entry — `value.value` is the actual block. */
@Serializable
internal data class NotionBlockEnvelope(
    @SerialName("spaceId") val spaceId: String? = null,
    val role: String? = null,
    /** Notion wraps each block in `{spaceId, value:{value:{...block...}, role}}`.
     *  We only care about the inner value, which we read via the [block] helper. */
    val value: JsonElement? = null,
)

/** Envelope around every collection entry. */
@Serializable
internal data class NotionCollectionEnvelope(
    @SerialName("spaceId") val spaceId: String? = null,
    val role: String? = null,
    val value: JsonElement? = null,
)

/**
 * Top-level response shape for `loadPageChunk` and `syncRecordValuesMain`.
 * The recordMap is the only field we actually consume; the cursor field
 * is reserved for streaming long pages but TechEmpower-sized trees fit
 * in chunkNumber=0 + limit=100.
 */
@Serializable
internal data class NotionChunkResponse(
    val cursor: JsonElement? = null,
    val recordMap: NotionRecordMap = NotionRecordMap(),
)

/**
 * Response shape for `queryCollection`. We pull the row block ids out
 * of `result.reducerResults.collection_group_results.blockIds` and
 * resolve them in `recordMap.block`.
 *
 * The `result.reducerResults.collection_group_results.total` field
 * reports the **full** row count in the collection regardless of the
 * per-call `limit` cap — we use it to drive pagination
 * ([rowsTotal]/[rowsReturned] helpers), since the unofficial API
 * doesn't expose a `has_more`/`next_cursor` token on this endpoint
 * (it caps a single call's payload and expects callers to widen
 * `limit` or re-issue).
 */
@Serializable
internal data class NotionQueryCollectionResponse(
    val result: JsonElement? = null,
    val recordMap: NotionRecordMap = NotionRecordMap(),
    val allBlockIds: List<String> = emptyList(),
)

/**
 * Read the **total** row count the server reports for this collection
 * out of `result.reducerResults.collection_group_results.total`.
 * Returns null when the field is missing or non-numeric (older
 * response shapes, or a reducer envelope we don't recognise).
 */
internal fun NotionQueryCollectionResponse.rowsTotal(): Int? {
    val res = result as? JsonObject ?: return null
    val reducers = res["reducerResults"] as? JsonObject ?: return null
    val group = reducers["collection_group_results"] as? JsonObject ?: return null
    val total = group["total"] as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return total.content.toIntOrNull()
}

/**
 * How many row block ids the server actually returned in
 * `result.reducerResults.collection_group_results.blockIds`. Counts
 * only string primitives in the array; non-string entries are skipped
 * defensively. Returns 0 when the field is missing.
 */
internal fun NotionQueryCollectionResponse.rowsReturned(): Int {
    val res = result as? JsonObject ?: return 0
    val reducers = res["reducerResults"] as? JsonObject ?: return 0
    val group = reducers["collection_group_results"] as? JsonObject ?: return 0
    val ids = group["blockIds"] as? JsonArray ?: return 0
    var n = 0
    for (e in ids) {
        val p = e as? kotlinx.serialization.json.JsonPrimitive ?: continue
        if (p.isString) n++
    }
    return n
}

/** `getPublicPageData` response. We use [publicAccessRole] /
 *  [requireLogin] as the "is this readable anonymously" probe. */
@Serializable
internal data class NotionPublicPageData(
    val pageId: String? = null,
    val spaceName: String? = null,
    val spaceId: String? = null,
    val spaceDomain: String? = null,
    val publicAccessRole: String? = null,
    val requireLogin: Boolean = false,
    val isDeleted: Boolean = false,
    val betaEnabled: Boolean = false,
)

/**
 * Structured Notion v3 error envelope. The unofficial API returns
 * `{isNotionError:true, errorId, name, debugMessage, message, status}`
 * on failure. We surface [message] (human) and decode the [name]
 * machine-readable identifier for telemetry.
 */
@Serializable
internal data class NotionUnofficialError(
    val isNotionError: Boolean = false,
    val errorId: String? = null,
    val name: String? = null,
    val debugMessage: String? = null,
    val message: String? = null,
    val status: Int? = null,
)

// ─── helpers ──────────────────────────────────────────────────────────

/**
 * Pull the inner block JSON out of an envelope. Notion wraps every
 * block as `{spaceId, value:{value:{...}, role:...}}`; we want the
 * inner object. Returns null when the envelope is malformed.
 */
internal fun NotionBlockEnvelope.block(): JsonObject? {
    val outer = value as? JsonObject ?: return null
    return outer["value"] as? JsonObject
}

/**
 * Pull the inner collection JSON the same way as [block].
 */
internal fun NotionCollectionEnvelope.collection(): JsonObject? {
    val outer = value as? JsonObject ?: return null
    return outer["value"] as? JsonObject
}

/**
 * Pull a child id list out of a block's `content` field. Notion's
 * unofficial API stores child block ids in display order under
 * `content:[uuid, uuid, ...]`. Returns empty list when the field is
 * missing or not an array.
 */
internal fun JsonObject.contentIds(): List<String> {
    val arr = this["content"] as? JsonArray ?: return emptyList()
    val out = ArrayList<String>(arr.size)
    for (e in arr) {
        val s = (e as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNullStrict() ?: continue
        out.add(s)
    }
    return out
}

/** Safe primitive→string extraction guarded against non-string primitives. */
internal fun kotlinx.serialization.json.JsonPrimitive.contentOrNullStrict(): String? =
    if (isString) content else null
