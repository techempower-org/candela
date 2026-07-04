package `in`.jphe.storyvox.data

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourceConfigContributor
import `in`.jphe.storyvox.data.source.plugin.SourceConfigField
import `in`.jphe.storyvox.data.source.plugin.SourceConfigValue
import `in`.jphe.storyvox.source.primegaming.config.PrimeGamingConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Issue #1531 — the three worked examples that prove the generic
 * config-field seam. Each wraps an existing `*ConfigImpl` (the DataStore /
 * EncryptedSharedPreferences backing store stays exactly where it was) and
 * exposes it through the [SourceConfigContributor] contract, so Settings
 * renders the fields with no bespoke row.
 *
 * These live in `:app` (not the leaf source modules) because that is where
 * the Android-backed `*ConfigImpl`s already live — the same split the
 * config impls themselves use to keep source modules free of Preferences
 * plumbing. A new credentialed source adds one contributor here (or an
 * out-of-tree equivalent) plus one `@IntoSet` binding in `AppBindings` —
 * and touches none of the three Settings monoliths.
 */

/**
 * Reddit installed-app BYOK (#1492): the client id (masked, write-only) plus
 * the top-comment epilogue toggle. Replaces the bespoke `RedditConfigRow` +
 * the `setRedditClientId` / `setRedditAppendTopComments` mutators.
 */
@Singleton
class RedditConfigContributor @Inject constructor(
    private val config: RedditConfigImpl,
) : SourceConfigContributor {
    override val sourceId: String = "reddit"
    override val displayName: String = "Reddit"
    override val sectionHelp: String =
        "Bring your own Reddit installed-app credential — full post bodies via " +
            "Reddit's API (~100 requests/min)."

    override fun fields(): List<SourceConfigField> = listOf(
        SourceConfigField.SecretText(
            key = "clientId",
            label = "Client id",
            help = "Create a free \"installed app\" at reddit.com/prefs/apps " +
                "(type: installed app — no secret) and paste its client id. " +
                "See docs/reddit-setup.md.",
            placeholder = "e.g. AbC1dEf2GhI3jK",
        ),
        SourceConfigField.Toggle(
            key = "appendTopComments",
            label = "Append top comments",
            help = "Narrate each post's top comments after its body.",
        ),
    )

    override val values: Flow<Map<String, SourceConfigValue>> = config.state.map { s ->
        mapOf(
            "clientId" to SourceConfigValue.Secret(s.clientId.isNotBlank()),
            "appendTopComments" to SourceConfigValue.Bool(s.appendTopComments),
        )
    }

    override suspend fun set(key: String, raw: String) {
        when (key) {
            "clientId" -> config.setClientId(raw.ifBlank { null })
            "appendTopComments" -> config.setAppendTopComments(raw.toBoolean())
        }
    }
}

/**
 * Notion (#233/#393): the database id (plain, shown) plus the Internal
 * Integration Token (masked, write-only). Replaces the bespoke
 * `NotionConfigRow` in Settings. The Browse "Manage Notion" sheet keeps its
 * own OAuth-aware surface via the repository's `setNotionApiToken` /
 * `setNotionDatabaseId` — both write through the same [NotionConfigImpl], so
 * there's a single source of truth in storage.
 */
@Singleton
class NotionConfigContributor @Inject constructor(
    private val config: NotionConfigImpl,
) : SourceConfigContributor {
    override val sourceId: String = SourceIds.NOTION
    override val displayName: String = "Notion"
    override val sectionHelp: String =
        "Reads TechEmpower's public Notion content by default. Paste an " +
            "Internal Integration Token (notion.so/my-integrations) to read " +
            "your own workspace database."

    override fun fields(): List<SourceConfigField> = listOf(
        SourceConfigField.PlainText(
            key = "databaseId",
            label = "Database ID",
            help = "The Notion database the source reads in token mode. " +
                "Defaults to TechEmpower's public database.",
            placeholder = "32-hex or hyphenated UUID",
        ),
        SourceConfigField.SecretText(
            key = "token",
            label = "Integration token",
            help = "Create one at notion.so/my-integrations, then share your " +
                "database with the integration.",
            placeholder = "ntn_…",
        ),
    )

    override val values: Flow<Map<String, SourceConfigValue>> = config.state.map { s ->
        mapOf(
            "databaseId" to SourceConfigValue.Text(s.databaseId),
            "token" to SourceConfigValue.Secret(s.apiToken.isNotBlank()),
        )
    }

    override suspend fun set(key: String, raw: String) {
        when (key) {
            "databaseId" -> config.setDatabaseId(raw)
            "token" -> config.setApiToken(raw.ifBlank { null })
        }
    }
}

/**
 * Prime Gaming feed-URL override (#1494/#1535): the single tunable, an
 * http(s) URL that defaults to the bundled LootScraper feed. This is the
 * first user-facing surface for the override — it was previously reachable
 * only in code, deferred to this seam. Blank reverts to the default.
 */
@Singleton
class PrimeGamingConfigContributor @Inject constructor(
    private val config: PrimeGamingConfigImpl,
) : SourceConfigContributor {
    override val sourceId: String = "primegaming"
    override val displayName: String = "Prime Gaming"
    override val sectionHelp: String =
        "The Amazon-Prime claims feed (community LootScraper Atom feed). " +
            "Override if the feed moves hosts or you self-host it."

    override fun fields(): List<SourceConfigField> = listOf(
        SourceConfigField.UrlText(
            key = "feedUrl",
            label = "Feed URL override",
            help = "Leave blank to use the bundled default feed.",
            placeholder = PrimeGamingConfig.DEFAULT_FEED_URL,
        ),
    )

    override val values: Flow<Map<String, SourceConfigValue>> = config.feedUrlFlow.map { url ->
        mapOf("feedUrl" to SourceConfigValue.Text(url))
    }

    override suspend fun set(key: String, raw: String) {
        when (key) {
            "feedUrl" -> config.setFeedUrl(raw.ifBlank { null })
        }
    }
}
