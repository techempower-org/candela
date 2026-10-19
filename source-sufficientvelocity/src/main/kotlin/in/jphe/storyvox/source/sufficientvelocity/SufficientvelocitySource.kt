package `in`.jphe.storyvox.source.sufficientvelocity

import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.spacebattles.engine.XfEngineSource
import `in`.jphe.storyvox.source.spacebattles.engine.XfSite
import `in`.jphe.storyvox.source.sufficientvelocity.net.SufficientvelocityApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sufficient Velocity — SpaceBattles' sister board, same XenForo software,
 * so all parsing is the shared [XfEngineSource] from `:source-spacebattles`.
 * Ids: `sufficientvelocity:<threadId>`, chapters `…::<postId>`.
 */
@SourcePlugin(
    id = "sufficientvelocity",
    displayName = "Sufficient Velocity",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Forum fiction · threadmarked stories, anonymous",
    sourceUrl = "https://forums.sufficientvelocity.com",
    chipLabel = "SV",
    searchHint = "Paste a Sufficient Velocity thread URL or id",
    iconName = "Forum",
)
@Singleton
internal class SufficientvelocitySource @Inject constructor(
    api: SufficientvelocityApi,
) : XfEngineSource(api, SITE) {

    companion object {
        val SITE = XfSite(
            id = "sufficientvelocity",
            displayName = "Sufficient Velocity",
            defaultForum = "user-fiction.2",
            forums = linkedMapOf(
                "User Fiction" to "user-fiction.2",
            ),
        )
    }
}
