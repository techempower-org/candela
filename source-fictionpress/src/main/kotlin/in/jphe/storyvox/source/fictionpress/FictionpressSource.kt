package `in`.jphe.storyvox.source.fictionpress

import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.fanfictionnet.engine.FfnEngineSource
import `in`.jphe.storyvox.source.fanfictionnet.engine.FfnSite
import `in`.jphe.storyvox.source.fictionpress.net.FictionpressApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FictionPress — FanFiction.net's sister site for original fiction and
 * poetry. Same site software, so all parsing is the shared [FfnEngineSource]
 * from `:source-fanfictionnet`. Ids: `fictionpress:<storyId>`.
 */
@SourcePlugin(
    id = "fictionpress",
    displayName = "FictionPress",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Original fiction & poetry · anonymous",
    sourceUrl = "https://www.fictionpress.com",
    searchHint = "Search FictionPress stories",
    iconName = "AutoStories",
)
@Singleton
internal class FictionpressSource @Inject constructor(
    api: FictionpressApi,
) : FfnEngineSource(api, SITE) {

    companion object {
        val SITE = FfnSite(
            id = "fictionpress",
            displayName = "FictionPress",
            defaultCategory = "fiction/Romance",
            categories = linkedMapOf(
                "Romance" to "fiction/Romance",
                "Fantasy" to "fiction/Fantasy",
                "General" to "fiction/General",
                "Sci-Fi" to "fiction/Sci-Fi",
                "Adventure" to "fiction/Adventure",
                "Drama" to "fiction/Drama",
                "Humor" to "fiction/Humor",
                "Horror" to "fiction/Horror",
                "Mystery" to "fiction/Mystery",
                "Action" to "fiction/Action",
                "Poetry" to "poetry/General",
            ),
        )
    }
}
