package `in`.jphe.storyvox.data.source

import android.util.Log

/**
 * Issue #981 — resolves a stored fiction id to the `sourceId` (the
 * `Map<String, FictionSource>` key) that can actually hydrate it.
 *
 * # Why this exists
 *
 * `LibrarySyncer`/`FollowsSyncer` historically derived the placeholder
 * row's sourceId as `id.substringBefore(':')`. That's correct for the
 * many sources whose fiction ids are `"<sourceId>:<rest>"`
 * (`gutenberg:84`, `ao3:123`, `notion:guides`, `wikipedia:Foo`, …) but
 * WRONG for two real id shapes the sync layer can receive:
 *
 *  - **Royal Road** ids are bare numbers (`"8894"`, `"146000"`) with no
 *    colon — the normal browse/detail path sets `sourceId = "royalroad"`
 *    explicitly, but `substringBefore(':')` on a colon-less string
 *    returns the *whole number*, yielding an unbindable sourceId.
 *  - **Radio** station ids are station-name-prefixed
 *    (`"somafm-groove-salad:live"`, `"kvmr:live"`); the bound key is
 *    `"radio"` (RadioSource resolves the station from the id internally),
 *    so the `somafm-groove-salad` prefix isn't a registered source.
 *
 * The result was placeholder rows stuck on "Loading…" forever because no
 * `FictionSource` could be looked up to fetch their metadata (issue
 * #981). This resolver is the single place that maps an id to the right
 * key, using the live set of bound source keys to validate each guess so
 * it degrades gracefully as sources are added/removed.
 *
 * # Resolution order
 *
 * Given the set of [boundSourceIds] (the keys of the production
 * `Map<String, FictionSource>`), the first candidate that is actually
 * bound wins:
 *  1. the id's colon prefix, if the id has a colon and that prefix is a
 *     bound key — the common, already-correct case;
 *  2. [SourceIds.ROYAL_ROAD] when the id has no colon — Royal Road is the
 *     only source with bare-id (numeric) fictions;
 *  3. [SourceIds.RADIO] when the id HAS a colon but the prefix wasn't a
 *     bound key — radio is the only source using station-name prefixes,
 *     and its ids always carry a colon (`station:live`, `rb:uuid`,
 *     `radio:custom:hash`).
 *
 * Returns null when nothing resolves (an id from a source whose module
 * isn't bound in this build) so callers can leave the row alone rather
 * than misroute it.
 */
object FictionSourceIdResolver {

    /**
     * Best-effort sourceId for [id], validated against [boundSourceIds].
     *
     * [storedSourceId] (the row's current `sourceId` column) is tried
     * first when it's already a bound key — a row written by the normal
     * browse path is correct and we don't want to second-guess it. Only
     * when the stored value doesn't resolve do we fall back to deriving
     * from the id shape.
     */
    fun resolve(
        id: String,
        boundSourceIds: Set<String>,
        storedSourceId: String? = null,
    ): String? {
        if (storedSourceId != null && storedSourceId in boundSourceIds) return storedSourceId

        val hasColon = id.contains(':')
        if (hasColon) {
            val prefix = id.substringBefore(':')
            if (prefix in boundSourceIds) return prefix
            // Colon-prefixed but the prefix isn't a registered source →
            // radio station id (only source using station-name prefixes).
            if (SourceIds.RADIO in boundSourceIds) return SourceIds.RADIO
            return null
        }

        // No colon → bare Royal Road numeric id (or a source that forgot
        // its prefix — #1564 warns loudly on the latter).
        warnIfMissingSourcePrefix(id)
        if (SourceIds.ROYAL_ROAD in boundSourceIds) return SourceIds.ROYAL_ROAD
        return null
    }

    /**
     * Issue #981 — shape-only sourceId guess for callers that don't have
     * the bound-source registry (the sync layer, which depends on
     * core-data but not the leaf source modules). Same id-shape rules as
     * [resolve] but without validation:
     *  - no colon → [SourceIds.ROYAL_ROAD] (bare numeric RR id);
     *  - colon prefix → the prefix verbatim.
     *
     * This is the value `LibrarySyncer`/`FollowsSyncer` should stamp on a
     * fresh placeholder so it's correct from the start for the common
     * cases. It deliberately does NOT special-case radio: a
     * `somafm-groove-salad:live` id resolves to the prefix here, and the
     * back-fill worker — which DOES have the bound registry — repairs it
     * to [SourceIds.RADIO] via [resolve] on its first pass. The worker is
     * the authoritative self-heal; this just stops the most common shape
     * (bare RR ids) from ever being written wrong.
     */
    fun resolveByShape(id: String): String {
        if (id.contains(':')) return id.substringBefore(':')
        warnIfMissingSourcePrefix(id)
        return SourceIds.ROYAL_ROAD
    }

    /**
     * Issue #1564 — a colon-less id that is NOT a bare number is almost
     * certainly a source that forgot the required `"<pluginId>:<localId>"`
     * prefix. Royal Road (the only bare-id source) uses numeric ids, radio
     * ids carry a colon, and every other source prefixes — so a colon-less
     * NON-numeric id is a routing bug: it silently defaults to Royal Road
     * (above), and its fiction's detail/reader never open.
     *
     * Pure (no Android types) so it is unit-testable without a logger.
     */
    internal fun looksLikeMissingSourcePrefix(id: String): Boolean =
        !id.contains(':') && id.toLongOrNull() == null

    /**
     * Log LOUD (not gated behind verbose logging, and not a throw — a
     * bad id from one source must not crash sync/back-fill for the rest)
     * when [id] looks like a missing-prefix routing bug (#1564). The
     * contract kit ([FictionSourceContractTest]) is the fail-fast,
     * test-time counterpart.
     */
    private fun warnIfMissingSourcePrefix(id: String) {
        if (looksLikeMissingSourcePrefix(id)) {
            Log.w(
                TAG,
                "Fiction id '$id' has no '<pluginId>:' prefix — routing it to " +
                    "Royal Road by default. A non-Royal-Road source returning bare " +
                    "ids will misroute (detail/reader won't open). Fiction ids must " +
                    "be \"<pluginId>:<localId>\" — see docs/CONTRIBUTING-SOURCES.md.",
            )
        }
    }

    private const val TAG = "FictionSrcIdResolver"
}
