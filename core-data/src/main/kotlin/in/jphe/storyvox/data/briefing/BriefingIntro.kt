package `in`.jphe.storyvox.data.briefing

/**
 * #1847 — the spoken-intro wording, one positional template per position in
 * the briefing: `%1$s` is the source label, `%2$s` the item title. Production
 * fills these from string resources (values + values-es) so the intro matches
 * the UI language; this module stays free of Android resources.
 */
data class BriefingIntroTemplates(
    /** The first item, and the only item of a one-item briefing ("From %1$s: %2$s."). */
    val first: String,
    /** Every item between the first and the last ("Next, from %1$s: %2$s."). */
    val next: String,
    /** The last item of a briefing with two or more ("Finally, from %1$s: %2$s."). */
    val last: String,
)

/**
 * #1847 — builds the short intro spoken before briefing [item] at [index] of
 * [total], so the queue plays as one continuous episode instead of separate
 * chapters. [labelOf] maps a source id to its display name (the plugin
 * registry, as FeedLogic's `labelOf`); a missing or blank label falls back to
 * the id. Pure, so it is unit-tested apart from the player that speaks it.
 */
fun briefingIntroFor(
    item: BriefingItem,
    index: Int,
    total: Int,
    labelOf: (String) -> String?,
    templates: BriefingIntroTemplates,
): String {
    require(index in 0 until total) { "briefing intro index $index outside 0 until $total" }
    val label = labelOf(item.sourceId)?.takeIf { it.isNotBlank() } ?: item.sourceId
    val template = when {
        index == 0 -> templates.first
        index == total - 1 -> templates.last
        else -> templates.next
    }
    return template.format(label, item.title.trim())
}
