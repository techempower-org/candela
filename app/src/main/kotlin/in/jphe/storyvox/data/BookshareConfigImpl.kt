package `in`.jphe.storyvox.data

import android.content.SharedPreferences
import `in`.jphe.storyvox.source.bookshare.BookshareConfig
import javax.inject.Inject
import javax.inject.Singleton

/** EncryptedSharedPreferences key for the Bookshare partner API key.
 *  Lives in the `storyvox.secrets` bag alongside the other source
 *  credentials (Discord/Notion tokens, `palace.api_key`), so it rides
 *  the same AES-GCM-at-rest + backup-exclusion posture as every other
 *  stored secret. */
internal const val BOOKSHARE_API_KEY_PREF = "pref_source_bookshare_api_key"

/**
 * Issue #1471 — production [BookshareConfig]. Supplies the partner
 * `api_key` from the encrypted `storyvox.secrets` bag, replacing the
 * no-op `InMemoryBookshareConfig` the source module used to bind.
 *
 * The moment a non-blank key is stored, `BookshareApi`'s discovery
 * surface (search / browse / categories) goes live — `BookshareSource`
 * stops short-circuiting those calls to `FictionResult.AuthRequired`.
 * When the key is blank/absent, behaviour is identical to today: every
 * Bookshare call is gated.
 *
 * [accessToken] stays `null`: the per-user OAuth2 flow is Stage 2b
 * (#1462), out of scope here, so member-scoped endpoints remain gated.
 * Content download (`fictionDetail` / `chapter` — Protected-DAISY) is
 * gated on the partner agreement (Stage 3) regardless of the api_key.
 *
 * Mirrors [DiscordConfigImpl] / [NotionConfigImpl]'s secrets-backed
 * token leg, minus the DataStore leg — Bookshare has no non-secret
 * config fields, so the api key is the whole surface.
 */
@Singleton
class BookshareConfigImpl @Inject constructor(
    private val secrets: SharedPreferences,
) : BookshareConfig {

    override suspend fun apiKey(): String? =
        secrets.getString(BOOKSHARE_API_KEY_PREF, null)?.ifBlank { null }

    override suspend fun accessToken(): String? = null

    /**
     * Settings-UI write hook. Kept off the [BookshareConfig] interface
     * so the source module reads but cannot write its own credentials
     * (same posture as [DiscordConfigImpl.setApiToken]). Null/blank
     * clears the entry, re-gating the source.
     */
    fun setApiKey(key: String?) {
        if (key.isNullOrBlank()) {
            secrets.edit().remove(BOOKSHARE_API_KEY_PREF).apply()
        } else {
            secrets.edit().putString(BOOKSHARE_API_KEY_PREF, key.trim()).apply()
        }
    }

    /** True when a non-blank partner key is stored. Drives the UI's
     *  `bookshareKeyConfigured` projection. */
    fun isKeyConfigured(): Boolean =
        !secrets.getString(BOOKSHARE_API_KEY_PREF, null).isNullOrBlank()
}
