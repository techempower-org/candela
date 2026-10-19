package `in`.jphe.storyvox.source.questionablequesting

import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.questionablequesting.net.QuestionablequestingApi
import `in`.jphe.storyvox.source.spacebattles.engine.XfEngineSource
import `in`.jphe.storyvox.source.spacebattles.engine.XfSite
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Questionable Questing — the SB/SV diaspora's 18+-tolerant board, same
 * XenForo software, so all parsing is the shared [XfEngineSource] from
 * `:source-spacebattles`. Ids: `questionablequesting:<threadId>`.
 *
 * The board hosts substantial adult content, so like `:source-aff` this
 * module is only linked into `:app` with `-Pcandela.adultSources=true` —
 * Play and default sideload builds never contain it (see app/build.gradle.kts).
 * The anonymous browse below reaches the SFW forums; NSFW sections need a
 * signed-in session, which isn't supported.
 *
 * Forum node ids verified against the live site (2026-10).
 */
@SourcePlugin(
    id = "questionablequesting",
    displayName = "Questionable Questing",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Forum fiction (18+ board) · threadmarked stories, anonymous",
    sourceUrl = "https://forum.questionablequesting.com",
    chipLabel = "QQ",
    searchHint = "Paste a QQ thread URL or id",
    iconName = "Forum",
)
@Singleton
internal class QuestionablequestingSource @Inject constructor(
    api: QuestionablequestingApi,
) : XfEngineSource(api, SITE) {

    companion object {
        val SITE = XfSite(
            id = "questionablequesting",
            displayName = "Questionable Questing",
            defaultForum = "creative-writing.19",
            forums = linkedMapOf(
                "Creative Writing" to "creative-writing.19",
                "Questing" to "questing.20",
                "Story Archive" to "story-archive.28",
                "Quest Archive" to "quest-archive.22",
            ),
        )
    }
}
