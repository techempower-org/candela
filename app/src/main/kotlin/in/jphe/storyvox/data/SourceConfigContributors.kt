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
        "Reads TechEMPOWER's public Notion content by default. Paste an " +
            "Internal Integration Token (notion.so/my-integrations) to read " +
            "your own workspace database."

    override fun fields(): List<SourceConfigField> = listOf(
        SourceConfigField.PlainText(
            key = "databaseId",
            label = "Database ID",
            help = "The Notion database the source reads in token mode. " +
                "Defaults to TechEMPOWER's public database.",
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

/**
 * Slack bot-token BYOK (#454, surfaced #1577). The `SlackConfigImpl` backing
 * store + setters shipped with the source, but the Settings UI the source's
 * own kdoc promised ("Settings → Library & Sync → Slack") never landed — so
 * the source was permanently gated to AuthRequired with no way to configure
 * it. Routing the token through this contributor lights it up with no bespoke
 * row. One token = one workspace (Slack has no multi-workspace token), so a
 * single masked field is the whole surface.
 */
@Singleton
class SlackConfigContributor @Inject constructor(
    private val config: SlackConfigImpl,
) : SourceConfigContributor {
    override val sourceId: String = SourceIds.SLACK
    override val displayName: String = "Slack"
    override val sectionHelp: String =
        "Read a Slack workspace's channels. Create an app at api.slack.com, " +
            "install it to your workspace, and paste its Bot User OAuth Token."

    override fun fields(): List<SourceConfigField> = listOf(
        SourceConfigField.SecretText(
            key = "token",
            label = "Bot token",
            help = "Bot User OAuth Token with the channels:read, channels:history, " +
                "groups:read, groups:history, and users:read scopes.",
            placeholder = "xoxb-…",
        ),
    )

    override val values: Flow<Map<String, SourceConfigValue>> = config.state.map { s ->
        mapOf("token" to SourceConfigValue.Secret(s.apiToken.isNotBlank()))
    }

    override suspend fun set(key: String, raw: String) {
        when (key) {
            "token" -> config.setApiToken(raw.ifBlank { null })
        }
    }
}

/**
 * Matrix access-token BYOK (#457, surfaced #1577). Same "Matrix precedent"
 * story as Slack — `MatrixConfigImpl` + setters shipped, the Settings UI never
 * did. The source needs BOTH a homeserver URL and an access token before it can
 * dispatch, so both are surfaced: homeserver as an editable URL field, token as
 * a write-only secret. (Blank homeserver clears it; the coalesce-window knob
 * keeps its default — the generic seam has no numeric field type yet.)
 */
@Singleton
class MatrixConfigContributor @Inject constructor(
    private val config: MatrixConfigImpl,
) : SourceConfigContributor {
    override val sourceId: String = SourceIds.MATRIX
    override val displayName: String = "Matrix"
    override val sectionHelp: String =
        "Read a Matrix room as a fiction. Paste your homeserver URL and an " +
            "access token (Element → Settings → Help & About → Advanced → Access Token). " +
            "No password login."

    override fun fields(): List<SourceConfigField> = listOf(
        SourceConfigField.UrlText(
            key = "homeserver",
            label = "Homeserver URL",
            help = "e.g. https://matrix.org, or your self-hosted Synapse / Dendrite / Conduit.",
            placeholder = "https://matrix.org",
        ),
        SourceConfigField.SecretText(
            key = "token",
            label = "Access token",
            help = "A homeserver access token (syt_… / mat_…) — never your password.",
            placeholder = "syt_…",
        ),
    )

    override val values: Flow<Map<String, SourceConfigValue>> = config.state.map { s ->
        mapOf(
            "homeserver" to SourceConfigValue.Text(s.homeserverUrl),
            "token" to SourceConfigValue.Secret(s.accessToken.isNotBlank()),
        )
    }

    override suspend fun set(key: String, raw: String) {
        when (key) {
            "homeserver" -> config.setHomeserverUrl(raw)
            "token" -> config.setAccessToken(raw.ifBlank { null })
        }
    }
}

/**
 * Issue #1591 — Palace Project (public-library OPDS) library config. Palace has
 * no canonical default library — each library is its own OPDS root — so the user
 * supplies their library's catalog URL. Surfaced through the generic seam
 * (renders in the Content Sources subscreen) as a single URL field wrapping
 * [PalaceLibraryConfigImpl], which the source reads via the PalaceLibraryConfig
 * contract. URL-only: Palace v1 reads public feeds, so no credentials.
 */
@Singleton
class PalaceConfigContributor @Inject constructor(
    private val config: PalaceLibraryConfigImpl,
) : SourceConfigContributor {
    override val sourceId: String = SourceIds.PALACE
    override val displayName: String = "Palace Project"
    override val sectionHelp: String =
        "Read ebooks from a public library's Palace Project (OPDS) catalog. " +
            "Paste your library's catalog root URL — some are at " +
            "<library>.palaceproject.io, some at a custom domain."

    override fun fields(): List<SourceConfigField> = listOf(
        SourceConfigField.UrlText(
            key = "libraryRootUrl",
            label = "Library catalog URL",
            help = "Your library's Palace / OPDS catalog root.",
            placeholder = "https://catalog.example.org",
        ),
    )

    override val values: Flow<Map<String, SourceConfigValue>> = config.libraryRootUrl.map { url ->
        mapOf("libraryRootUrl" to SourceConfigValue.Text(url ?: ""))
    }

    override suspend fun set(key: String, raw: String) {
        if (key == "libraryRootUrl") config.setRootUrl(raw.ifBlank { null })
    }
}

/**
 * Issue #1624 — Outline (#245) migrated onto the generic seam. Was a bespoke
 * `OutlineConfigRow` (host + API key) in the legacy Settings monolith; now a
 * host URL + write-only key through the seam, so it renders in the Content
 * Sources subscreen (#1630) with no bespoke row. Wraps the same
 * [OutlineConfigImpl] that `SettingsRepositoryUi.setOutlineHost/ApiKey` write
 * to, so storage keeps a single source of truth.
 */
@Singleton
class OutlineConfigContributor @Inject constructor(
    private val config: OutlineConfigImpl,
) : SourceConfigContributor {
    override val sourceId: String = SourceIds.OUTLINE
    override val displayName: String = "Outline"
    override val sectionHelp: String =
        "Read documents from an Outline knowledge base. Enter your instance " +
            "URL and a personal API token (Outline → Settings → API Tokens)."

    override fun fields(): List<SourceConfigField> = listOf(
        SourceConfigField.UrlText(
            key = "host",
            label = "Outline URL",
            help = "Your Outline instance, e.g. https://wiki.example.com.",
            placeholder = "https://outline.example.com",
        ),
        SourceConfigField.SecretText(
            key = "apiKey",
            label = "API key",
            help = "A personal API token from your Outline account.",
        ),
    )

    override val values: Flow<Map<String, SourceConfigValue>> = config.state.map { s ->
        mapOf(
            "host" to SourceConfigValue.Text(s.host),
            "apiKey" to SourceConfigValue.Secret(s.apiKey.isNotBlank()),
        )
    }

    override suspend fun set(key: String, raw: String) {
        when (key) {
            "host" -> config.setHost(raw)
            "apiKey" -> config.setApiKey(raw)
        }
    }
}

/**
 * Issue #1624 — Wikipedia (#377) migrated onto the generic seam. Was a bespoke
 * `WikipediaLanguageRow` (free-text language code) in the legacy monolith; now
 * a single plain field through the seam. Wraps the same [WikipediaConfigImpl]
 * that `SettingsRepositoryUi.setWikipediaLanguageCode` writes to (single source
 * of truth). Blank reverts to the default language.
 *
 * Free text here matches the pre-migration behaviour; a validated dropdown
 * wants a `Choice` field type the seam doesn't have yet (deferred — see
 * content-sources-fields.md §D).
 */
@Singleton
class WikipediaConfigContributor @Inject constructor(
    private val config: WikipediaConfigImpl,
) : SourceConfigContributor {
    override val sourceId: String = SourceIds.WIKIPEDIA
    override val displayName: String = "Wikipedia"
    override val sectionHelp: String =
        "Read Wikipedia articles. Set the language edition to read from " +
            "(e.g. en, de, ja, simple). Leave blank for the default."

    override fun fields(): List<SourceConfigField> = listOf(
        SourceConfigField.PlainText(
            key = "languageCode",
            label = "Language code",
            help = "Wikipedia language edition, e.g. en or es. Blank uses the default.",
            placeholder = "en",
        ),
    )

    override val values: Flow<Map<String, SourceConfigValue>> = config.state.map { s ->
        mapOf("languageCode" to SourceConfigValue.Text(s.languageCode))
    }

    override suspend fun set(key: String, raw: String) {
        when (key) {
            "languageCode" -> config.setLanguageCode(raw)
        }
    }
}
