package `in`.jphe.storyvox.source.spacebattles

import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.spacebattles.engine.XfEngineSource
import `in`.jphe.storyvox.source.spacebattles.engine.XfSite
import `in`.jphe.storyvox.source.spacebattles.net.SpacebattlesApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SpaceBattles — the Creative Writing forum's threadmarked stories. Ids:
 * `spacebattles:<threadId>`, chapters `…::<postId>`. All parsing lives in the
 * shared [XfEngineSource] (also used by `:source-sufficientvelocity` and
 * `:source-questionablequesting`).
 *
 * SpaceBattles fronts anonymous traffic with Cloudflare's managed challenge,
 * so requests can come back as `FictionResult.Cloudflare` until a WebView
 * clearance path is wired — the same posture as FanFiction.net.
 */
@SourcePlugin(
    id = "spacebattles",
    displayName = "SpaceBattles",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Forum fiction · threadmarked stories, anonymous",
    sourceUrl = "https://forums.spacebattles.com",
    chipLabel = "SB",
    searchHint = "Paste a SpaceBattles thread URL or id",
    iconName = "Forum",
)
@Singleton
internal class SpacebattlesSource @Inject constructor(
    api: SpacebattlesApi,
) : XfEngineSource(api, SITE) {

    companion object {
        val SITE = XfSite(
            id = "spacebattles",
            displayName = "SpaceBattles",
            defaultForum = "creative-writing.18",
            forums = linkedMapOf(
                "Creative Writing" to "creative-writing.18",
            ),
        )
    }
}
