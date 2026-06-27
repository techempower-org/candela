package `in`.jphe.storyvox.sync.domain

import android.content.SharedPreferences
import androidx.core.content.edit
import `in`.jphe.storyvox.sync.SyncIds
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.ConflictPolicies
import `in`.jphe.storyvox.sync.coordinator.Stamped
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import `in`.jphe.storyvox.sync.crypto.UserDerivedKey
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Source of the user's sync passphrase. Returns null when the user hasn't
 * configured one yet — secrets sync is then a no-op (returns
 * [SyncOutcome.Permanent]; the coordinator surfaces that to the UI so the
 * user can set the passphrase and retry).
 *
 * Modeled as an interface (not `() -> CharArray?`) so Hilt can resolve a
 * `@Named` binding without the function-type quirks. The default binding
 * in `:core-sync` returns null; `:app` (or `:feature` once the Settings UI
 * lands) overrides with a real lookup against EncryptedSharedPreferences
 * or an in-memory unlocked-session cache.
 */
fun interface PassphraseProvider {
    fun get(): CharArray?
}

/**
 * Write-side extension of [PassphraseProvider] — used by the settings UI
 * to let the user set, clear, and check the passphrase. The syncer only
 * needs [PassphraseProvider.get]; this interface is for the Account screen.
 */
interface PassphraseManager : PassphraseProvider {
    fun set(passphrase: CharArray)
    fun clear()
    fun isSet(): Boolean
}

/**
 * Syncs the user's encrypted secrets — API keys (Claude, OpenAI, etc.),
 * Royal Road session cookies, GitHub PAT, Outline tokens, Notion
 * integration token, Discord bot token.
 *
 * NEVER pushes plaintext. The flow is:
 *  1. Snapshot the relevant keys out of [EncryptedSharedPreferences].
 *  2. Serialize to a single JSON map.
 *  3. Encrypt with a [UserDerivedKey] derived from the user's passphrase.
 *  4. Push the AES-GCM envelope (which contains its own salt + iv) as
 *     the blob to InstantDB.
 *
 * If the user hasn't set a passphrase, this syncer is a no-op — secrets
 * stay local-only and the user is warned in Settings that their secrets
 * will need to be re-entered on reinstall. We don't fall back to
 * "push plaintext" — that would silently break the threat model.
 *
 * ## Field-level merge (#1162)
 *
 * Conflict resolution used to be whole-bag last-write-wins on the
 * envelope: the side with the newer `updatedAt` won and the loser's
 * entire bag was discarded. That silently dropped concurrent
 * cross-device edits — device A adds an OpenAI key, device B adds a
 * Slack token, B's bag wins on timestamp, A's OpenAI key is gone (and
 * vice-versa). Same gap settings closed in #978.
 *
 * This syncer now reconciles **per-secret**: each entry carries its own
 * `updatedAt` and the merge is a union with newest-per-key-wins (see
 * [ConflictPolicies.mergeStampedMap], shared with [SettingsSyncer]).
 * Two devices adding two different secrets both survive a round-trip.
 * The blob is opaque on the wire, but both sides are plaintext by the
 * time the merge runs (we decrypt local AND remote first), so the merge
 * is identical to settings' — only the IO is encrypted.
 *
 * ### Wire format (v2) and back-compat
 *
 * The per-secret timestamps live **inside** the encrypted envelope so
 * they stay private. The decrypted plaintext is still a flat
 * `{key: value}` JSON map (the v1 shape) plus two reserved string
 * entries an old client ignores as non-secret keys: `"_v": "2"` and
 * `"_field_stamps": "<json {key: updatedAt}>"` (stamps stringified so
 * every top-level value stays a String — the same trick settings use).
 *
 * Neither read direction loses data:
 *  - **v2 reads a v1 envelope** (no `_field_stamps`): synthesize every
 *    secret's stamp from the row's `updatedAt` — the only timestamp a
 *    v1 blob ever had — then merge per-key.
 *  - **old (v1) client reads a v2 envelope**: it decrypts the flat map
 *    and drops `_v` / `_field_stamps` on the floor (they fail the
 *    [isSecretKey] allowlist), keeping every real secret. It degrades to
 *    blob-LWW until it upgrades; it never corrupts the real entries.
 *
 * No migration job: the first push from a #1162 device rewrites that
 * user's row to v2 in place (lazy, per-user). Concurrency: the
 * [SyncCoordinator] serialises push/pull per domain, so the
 * fetch→merge→upsert round has no internal race.
 *
 * Key scope (which prefs get synced): everything matching one of
 * [SECRET_KEY_PREFIXES], plus the explicit-name entries in
 * [SECRET_KEY_NAMES]. Hardcoded to avoid accidentally syncing
 * device-local secrets (Tink master-key wrappers shouldn't sync —
 * they're already KeyStore-bound).
 *
 * **Why both a prefix list and a name list?** Most secrets follow a
 * dotted-prefix convention (`llm.openai_key`, `cookie:royalroad`,
 * `github.access_token`) so a prefix match handles them cheaply.
 * But the `:source-notion` and `:source-discord` configs predate
 * that convention — they pushed their secret to
 * EncryptedSharedPreferences under flat keys
 * (`pref_source_notion_token`, `pref_source_discord_token`) that
 * don't share a useful prefix without also catching unrelated
 * preferences. A prefix that's broad enough to match would also
 * match the non-secret toggles like `pref_source_notion_enabled`
 * — so we use an explicit name list for those two.
 */
@Singleton
class SecretsSyncer @Inject constructor(
    private val secrets: SharedPreferences,
    private val backend: InstantBackend,
    @Named(PASSPHRASE_PROVIDER) private val passphraseProvider: PassphraseProvider,
) : Syncer {

    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val stampSerializer = MapSerializer(String.serializer(), Long.serializer())

    override val name: String get() = DOMAIN

    override suspend fun push(user: SignedInUser): SyncOutcome = reconcile(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = reconcile(user)

    private suspend fun reconcile(user: SignedInUser): SyncOutcome {
        val passphrase = passphraseProvider.get()
            ?: return SyncOutcome.Permanent(
                "secrets sync requires a sync passphrase — set one in Settings → Account",
            )

        val key = try {
            // Salt is consistent for the user's secrets — we use a
            // deterministic salt derived from the userId so the same
            // user on a new device can decrypt. The InstantDB user id
            // is opaque and stable across sessions.
            UserDerivedKey.deriveKey(passphrase, deterministicSaltFor(user))
        } finally {
            passphrase.fill(' ')
        }

        val local = readLocalStamped()

        val remote = backend.fetch(user, ENTITY, rowId(user)).getOrElse {
            return SyncOutcome.Transient("remote fetch: ${it.message}")
        }

        // Issue #1027 (data loss): a remote row that EXISTS but cannot be
        // decrypted/parsed is NOT the same as "no remote row." The old
        // code collapsed both into `null`, picked local, and then pushed
        // unconditionally — re-encrypting local secrets under the wrong
        // key and silently destroying a perfectly good remote blob that a
        // sibling device (holding the correct passphrase) still needed.
        //
        // We now distinguish the two. A present-but-opaque row means the
        // passphrase doesn't match what the blob was encrypted with (wrong
        // passphrase typed, or rotated on another device) — or, far more
        // rarely, a garbled fetch. Either way we MUST NOT push: bail with a
        // Permanent outcome so the coordinator stops retrying and the UI
        // can prompt for the right passphrase, and leave both the remote
        // blob and local prefs untouched.
        val remoteDecoded: Map<String, Stamped<String>>? = when (val d = remote?.let { decode(it.payload, it.updatedAt, key) }) {
            null -> null // no remote row at all — safe to originate from local
            is DecodeResult.Undecryptable -> return SyncOutcome.Permanent(PASSPHRASE_MISMATCH_MESSAGE)
            is DecodeResult.Decoded -> d.entries
        }

        // Empty-side fast paths (mirror SettingsSyncer). These avoid a
        // pointless re-encrypt+push when one side has nothing to merge.
        when {
            local.isEmpty() && remoteDecoded.isNullOrEmpty() -> return SyncOutcome.Ok(0)
            local.isEmpty() -> {
                // Fresh device — adopt the remote bag wholesale. No push:
                // we'd just re-encrypt the identical entries.
                applyLocal(remoteDecoded!!)
                return SyncOutcome.Ok(remoteDecoded.size)
            }
            remoteDecoded.isNullOrEmpty() -> {
                // No remote row (or it decoded empty) — originate from
                // local. Reaching here for a present row means it decoded
                // cleanly (the #1027 Undecryptable case already returned).
                return pushMerged(user, local, key) ?: SyncOutcome.Ok(local.size)
            }
        }

        // Field-level union merge — the #1162 fix. A secret present on
        // only one side survives; a secret on both sides resolves to the
        // newer per-key stamp. This is exactly what dropped concurrent
        // different-key adds under the old whole-bag LWW.
        val merged = ConflictPolicies.mergeStampedMap(local, remoteDecoded!!)

        if (merged != local) {
            applyLocal(merged)
        }
        if (merged != remoteDecoded) {
            // Push merged back. Reaching here guarantees the remote row
            // decoded cleanly under this key — so this push never clobbers
            // an opaque-but-present remote blob (the #1027 invariant).
            pushMerged(user, merged, key)?.let { return it }
        }
        return SyncOutcome.Ok(merged.size)
    }

    /**
     * Encrypt [merged] and upsert it. Returns null on success, or the
     * [SyncOutcome.Transient] to propagate on a push failure.
     */
    private suspend fun pushMerged(
        user: SignedInUser,
        merged: Map<String, Stamped<String>>,
        key: SecretKey,
    ): SyncOutcome? {
        val envelope = encode(merged, key)
        val pushed = backend.upsert(
            user = user,
            entity = ENTITY,
            id = rowId(user),
            payload = envelope,
            updatedAt = rowStamp(merged),
        )
        return if (pushed.isSuccess) null
        else SyncOutcome.Transient("remote push: ${pushed.exceptionOrNull()?.message}")
    }

    /**
     * Read the local secrets as a per-key stamped map — the local input
     * to [ConflictPolicies.mergeStampedMap].
     *
     * Each secret's stamp comes from the persisted [SECRET_FIELD_STAMPS_KEY]
     * sidecar (a JSON `{key: updatedAt}` map kept in the same
     * EncryptedSharedPreferences, so it's encrypted at rest too). A secret
     * with no recorded per-key stamp yet — freshly typed since the last
     * sync, or pre-existing from before this upgrade — falls back to the
     * legacy bag-level [LAST_TOUCH_KEY] if set, else `now`.
     *
     * Issue #979 (carried forward): a freshly-stamped key is **materialised
     * and persisted** here. Persisting is what keeps LWW symmetric — the
     * stamp is fixed once and then stable across reconciles, so a genuinely
     * newer remote can still out-stamp it on the next round. Recomputing
     * `now` every reconcile would make local perpetually "newest" and
     * silently re-break the pull side.
     *
     * Empty local → empty map: a device with no secrets must not originate
     * or out-stamp a real remote blob; [reconcile]'s empty-side fast paths
     * handle that.
     */
    private fun readLocalStamped(): Map<String, Stamped<String>> {
        val snap = snapshotLocal()
        if (snap.isEmpty()) return emptyMap()
        val persisted = loadFieldStamps()
        val fallback = secrets.getLong(LAST_TOUCH_KEY, 0L).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val stamps = LinkedHashMap(persisted)
        var materialisedAny = false
        val out = LinkedHashMap<String, Stamped<String>>(snap.size)
        for ((k, v) in snap) {
            val stamp = persisted[k] ?: fallback.also { stamps[k] = it; materialisedAny = true }
            out[k] = Stamped(v, stamp)
        }
        if (materialisedAny) {
            secrets.edit {
                putString(SECRET_FIELD_STAMPS_KEY, json.encodeToString(stampSerializer, stamps))
                // Keep the legacy bag stamp advanced to the newest field —
                // it's the v1 fallback any pre-#1162 reader still uses.
                putLong(LAST_TOUCH_KEY, stamps.values.maxOrNull() ?: fallback)
            }
        }
        return out
    }

    private fun snapshotLocal(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val all = secrets.all
        for ((k, v) in all) {
            if (!isSecretKey(k)) continue
            if (v is String) out[k] = v
        }
        return out
    }

    /** Load the persisted per-key stamp sidecar, or empty on first run /
     *  a corrupt entry (treated as "no stamps yet"). */
    private fun loadFieldStamps(): Map<String, Long> {
        val raw = secrets.getString(SECRET_FIELD_STAMPS_KEY, null) ?: return emptyMap()
        return runCatching { json.decodeFromString(stampSerializer, raw) }.getOrDefault(emptyMap())
    }

    /**
     * Write the merged bag to local prefs and persist its per-key stamps.
     *
     * We only write keys that pass the [isSecretKey] allowlist — a
     * forward-compat clause so a future build pushing an unrecognised key
     * can't land arbitrary attacker-controlled entries in
     * EncryptedSharedPreferences. Keys absent from [merged] are left
     * untouched (no delete semantic — a cleared secret is encoded as the
     * empty string, not a removed key).
     */
    private fun applyLocal(merged: Map<String, Stamped<String>>) {
        val stamps = loadFieldStamps().toMutableMap()
        secrets.edit {
            for ((k, sv) in merged) {
                if (!isSecretKey(k)) continue
                putString(k, sv.value)
                stamps[k] = sv.updatedAt
            }
            putString(SECRET_FIELD_STAMPS_KEY, json.encodeToString(stampSerializer, stamps))
            rowStampOrNull(merged)?.let { putLong(LAST_TOUCH_KEY, it) }
        }
    }

    private fun isSecretKey(key: String): Boolean =
        key in SECRET_KEY_NAMES || SECRET_KEY_PREFIXES.any { key.startsWith(it) }

    /**
     * Encrypt [merged] as the v2 payload: the flat `{key: value}` map
     * (v1-readable once decrypted) plus the reserved `_v` and stringified
     * `_field_stamps` entries. Keys are sorted so byte-identical input
     * yields a byte-identical envelope — no no-op push thrash.
     *
     * Issue #360 finding 5 (argus): the v1 envelope stored a fresh
     * per-encrypt salt, but the AES key was actually derived from
     * [deterministicSaltFor] — the envelope salt was unused on decrypt.
     * Cross-device decrypt has always worked by both devices independently
     * recomputing the deterministic salt; the envelope's random salt was
     * dead bytes. The current envelope drops the salt slot — see
     * [UserDerivedKey.envelope] for the design.
     */
    private fun encode(merged: Map<String, Stamped<String>>, key: SecretKey): String {
        val flat = sortedMapOf<String, String>()
        val stamps = sortedMapOf<String, Long>()
        for ((k, sv) in merged) {
            flat[k] = sv.value
            stamps[k] = sv.updatedAt
        }
        val out = LinkedHashMap<String, String>(flat.size + 2)
        out.putAll(flat)
        out[KEY_VERSION] = WIRE_VERSION.toString()
        out[KEY_FIELD_STAMPS] = json.encodeToString(stampSerializer, stamps)
        val plaintext = json.encodeToString(mapSerializer, out).encodeToByteArray()
        val blob = UserDerivedKey.encrypt(key, plaintext)
        return UserDerivedKey.envelope(blob)
    }

    /** Row-level `updatedAt` for an upsert — the newest per-key stamp, so
     *  the v1 fallback an old reader sees is the most recent edit. */
    private fun rowStamp(merged: Map<String, Stamped<String>>): Long =
        rowStampOrNull(merged) ?: System.currentTimeMillis()

    private fun rowStampOrNull(merged: Map<String, Stamped<String>>): Long? =
        merged.values.maxOfOrNull { it.updatedAt }

    /**
     * Outcome of decoding a remote secrets row that we KNOW exists.
     *
     * Issue #1027: the caller must be able to tell "decoded cleanly" from
     * "row is present but we can't read it" — the latter is the
     * data-loss-causing case and must never lead to a push. ([reconcile]
     * handles "no remote row at all" separately, before calling [decode],
     * via the nullable `remote?.let { … }`.)
     */
    private sealed interface DecodeResult {
        data class Decoded(val entries: Map<String, Stamped<String>>) : DecodeResult

        /**
         * The envelope failed to parse, failed to decrypt (AES-GCM tag
         * mismatch → wrong passphrase), or decrypted to non-JSON. The row
         * exists on the server and belongs to someone's correct
         * passphrase — overwriting it would be data loss.
         */
        data object Undecryptable : DecodeResult
    }

    /**
     * Decrypt and parse a remote row into a per-key stamped map. Handles
     * both wire shapes (see the class kdoc):
     *  - **v2**: strip the reserved `_v` / `_field_stamps` entries and use
     *    the parsed stamp sidecar; a key missing from the sidecar falls
     *    back to the row's [updatedAt].
     *  - **v1** (no `_field_stamps`): every secret inherits the row's
     *    [updatedAt] — the only timestamp a v1 blob ever had.
     */
    private fun decode(envelope: String, updatedAt: Long, key: SecretKey): DecodeResult {
        val parsed = UserDerivedKey.parseEnvelope(envelope) ?: return DecodeResult.Undecryptable
        // AES-GCM authentication failure (wrong key) surfaces as an
        // AEADBadTagException out of cipher.doFinal; any decrypt throw is
        // treated as "can't read this row," never as "no row."
        val plain = runCatching { UserDerivedKey.decrypt(key, parsed.blob) }.getOrNull()
            ?: return DecodeResult.Undecryptable
        val raw = runCatching {
            json.decodeFromString(mapSerializer, plain.decodeToString())
        }.getOrNull() ?: return DecodeResult.Undecryptable
        val perKey: Map<String, Long> = raw[KEY_FIELD_STAMPS]?.let { stampsJson ->
            runCatching { json.decodeFromString(stampSerializer, stampsJson) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val out = LinkedHashMap<String, Stamped<String>>(raw.size)
        for ((k, v) in raw) {
            if (k == KEY_VERSION || k == KEY_FIELD_STAMPS) continue
            out[k] = Stamped(v, perKey[k] ?: updatedAt)
        }
        return DecodeResult.Decoded(out)
    }

    /** A 16-byte salt derived from the userId. Stable across devices for
     *  the same user, so a passphrase + userId combo always derives the
     *  same key. SHA-256 → first 16 bytes. */
    private fun deterministicSaltFor(user: SignedInUser): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(("storyvox-secrets:${user.userId}").toByteArray(Charsets.UTF_8)).copyOf(16)
    }

    private fun rowId(user: SignedInUser) = SyncIds.rowUuid(DOMAIN, user.userId)

    companion object {
        const val DOMAIN: String = "secrets"
        private const val ENTITY = "blobs"
        private const val LAST_TOUCH_KEY = "instantdb.secrets_synced_at"

        /**
         * Local-only prefs key holding the per-secret stamp sidecar — a
         * JSON `{key: updatedAt}` map. Lives in the same
         * EncryptedSharedPreferences as the secrets (so it's encrypted at
         * rest) but is never itself a synced secret: it fails [isSecretKey]
         * and so is skipped by [snapshotLocal]. Issue #1162.
         */
        private const val SECRET_FIELD_STAMPS_KEY = "instantdb.secrets_field_stamps"

        /** v2 = per-secret field-level merge (#1162). v1 envelopes have no
         *  version marker and decode as flat blob-LWW. */
        private const val WIRE_VERSION: Int = 2

        /**
         * Reserved keys inside the encrypted plaintext. Underscore-prefixed
         * so they can't collide with a real synced secret (every allowlisted
         * key is namespaced `llm.` / `cookie:` / `pref_source_*` etc., none
         * start with `_`) and so a pre-#1162 reader drops them via the
         * [isSecretKey] allowlist instead of writing them to prefs.
         */
        private const val KEY_VERSION: String = "_v"
        private const val KEY_FIELD_STAMPS: String = "_field_stamps"

        /**
         * Issue #1027 — user-facing outcome when the remote secrets blob
         * exists but can't be decrypted with this device's passphrase.
         * Surfaced via [SyncOutcome.Permanent] so the coordinator stops
         * retrying and the Account screen can prompt the user to re-enter
         * the passphrase used on their other device — instead of silently
         * overwriting the remote blob (the data-loss bug this fixes).
         */
        const val PASSPHRASE_MISMATCH_MESSAGE: String =
            "secrets: passphrase does not match the synced data — re-enter the passphrase used on your other device"

        /**
         * Hilt @Named qualifier for the passphrase provider binding.
         *
         * Why a named binding and not a plain `() -> CharArray?`: Hilt can't
         * resolve raw function types out of the box, and we want the
         * provider to be swappable per build (a default "no passphrase
         * configured" binding in `:core-sync`, overridable by `:app` /
         * Settings once the UI surface lands).
         */
        const val PASSPHRASE_PROVIDER: String = "secretsSyncerPassphraseProvider"

        /**
         * Allowlist of EncryptedSharedPreferences key prefixes that get
         * pushed to the cloud. Anything not in here AND not in
         * [SECRET_KEY_NAMES] stays local. Prefixes match where the rest
         * of the app stores secrets today:
         *  - `llm.*` for AI provider API keys (OpenAI, Anthropic
         *    direct, Vertex SA JSON, Azure key, Bedrock)
         *  - `cookie:*` for the per-source session cookies (RR,
         *    Outline, etc.)
         *  - `github.*` for GitHub OAuth state (access token, refresh
         *    token)
         *  - `anthropic.*` for Anthropic Teams refresh token
         *  - `outline.*` for Outline API key + base URL bits
         *  - `notion.*` for the Notion integration token
         *
         * Tink master-key wrappers (which are KeyStore-bound and can't
         * decrypt on another device anyway) live under `__androidx_security_*`
         * and are deliberately not listed.
         */
        val SECRET_KEY_PREFIXES: List<String> = listOf(
            "llm.",
            "cookie:",
            "github.",
            "anthropic.",
            "outline.",
            "notion.",
        )

        /**
         * Exact-match allowlist for secrets that don't follow the
         * dotted-prefix convention. Added as a Tier 2 extension in this
         * PR — the Discord bot token uses the legacy `pref_source_*`
         * naming, and broadening the prefix to `pref_source_` would
         * sweep in dozens of non-secret per-source toggles like
         * `pref_source_discord_enabled`.
         *
         * Each name here must be a high-sensitivity token whose leak
         * would be at least as serious as the contents of
         * [SECRET_KEY_PREFIXES]. Don't add non-secrets here — Tier 1
         * settings go through the plaintext [SettingsSyncer] blob.
         */
        val SECRET_KEY_NAMES: Set<String> = setOf(
            "pref_source_discord_token",
            // Issue #454 — Slack Bot Token (xoxb-…). Same posture as
            // the Discord token: high-sensitivity workspace-scoped
            // credential whose leak would expose the user's channel
            // history. Synced through InstantDB to the user's other
            // devices so a token paste on one device propagates.
            "pref_source_slack_token",
            // Issue #457 — Matrix backend access token. Same
            // `pref_source_*` naming convention as Discord; lives in
            // EncryptedSharedPreferences (`storyvox.secrets`) and
            // syncs cross-device so a user who configures Matrix on
            // their phone gets the same homeserver-token pair on
            // their tablet without re-running the access-token
            // create flow on their homeserver.
            "pref_source_matrix_token",
        )
    }
}
