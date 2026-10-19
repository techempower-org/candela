package `in`.jphe.storyvox.source.fanfictionnet

import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.fanfictionnet.engine.FfnEngineSource
import `in`.jphe.storyvox.source.fanfictionnet.engine.FfnSite
import `in`.jphe.storyvox.source.fanfictionnet.net.FanfictionnetApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FanFiction.net — the largest fanfic archive. Ids: `fanfictionnet:<storyId>`,
 * chapters `fanfictionnet:<storyId>::<n>`. Parsing lives in the shared
 * [FfnEngineSource] (also used by `:source-fictionpress`).
 *
 * FFN fronts every page with Cloudflare's managed challenge, so requests can
 * come back as `FictionResult.Cloudflare` until a WebView clearance path is
 * wired (Candela's app-wide `WebViewFetcher` is still a stub).
 */
@SourcePlugin(
    id = "fanfictionnet",
    displayName = "FanFiction.net",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Fan fiction · anonymous, K–T ratings in browse",
    sourceUrl = "https://www.fanfiction.net",
    chipLabel = "FFN",
    searchHint = "Search FanFiction.net stories",
    iconName = "AutoStories",
)
@Singleton
internal class FanfictionnetSource @Inject constructor(
    api: FanfictionnetApi,
) : FfnEngineSource(api, SITE) {

    companion object {
        val SITE = FfnSite(
            id = "fanfictionnet",
            displayName = "FanFiction.net",
            defaultCategory = "book/Harry-Potter",
            categories = linkedMapOf(
                "Harry Potter" to "book/Harry-Potter",
                "Naruto" to "anime/Naruto",
                "Twilight" to "book/Twilight",
                "Supernatural" to "tv/Supernatural",
                "Percy Jackson and the Olympians" to "book/Percy-Jackson-and-the-Olympians",
                "Lord of the Rings" to "book/Lord-of-the-Rings",
                "Star Wars" to "movie/Star-Wars",
                "Avengers" to "movie/Avengers",
                "Doctor Who" to "tv/Doctor-Who",
                "Sherlock" to "tv/Sherlock",
                "Glee" to "tv/Glee",
                "Inuyasha" to "anime/Inuyasha",
                "Bleach" to "anime/Bleach",
                "Kingdom Hearts" to "game/Kingdom-Hearts",
                "Legend of Zelda" to "game/Legend-of-Zelda",
            ),
        )
    }
}
