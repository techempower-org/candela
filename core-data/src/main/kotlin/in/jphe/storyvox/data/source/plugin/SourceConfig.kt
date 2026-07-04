package `in`.jphe.storyvox.data.source.plugin

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1531 — generic per-source **config-field seam**.
 *
 * ## The problem this solves
 *
 * `@SourcePlugin` (#384/#1371) gave a new backend a zero-central-edit
 * *Settings auto-section* (the Plugin Manager toggle + chip). But there
 * was **no generic way for a source to declare a config *field*** — a
 * credential entry, a URL override, a behaviour toggle. The only
 * mechanism was a hand-written composable plus a four-file monolith edit
 * (`SettingsScreen.kt` + `UiContracts.kt` + `SettingsViewModel.kt` +
 * `SettingsRepositoryUiImpl.kt`), repeated once per credentialed source —
 * and those are the highest-churn files in the repo, so N sibling source
 * PRs each adding a config row collided and broke auto-merge.
 *
 * ## The seam
 *
 * A source contributes a [SourceConfigContributor] (bound `@IntoSet` in
 * `:app`, next to where its `*ConfigImpl` already lives). Each contributor
 * declares a list of typed [SourceConfigField]s and exposes a live value
 * stream + a generic setter. The Settings layer renders **every**
 * contributor's fields through one registry-driven section — so adding a
 * credentialed source is now **zero** edits to the three Settings
 * monoliths. That restores auto-merge for source waves and closes the
 * last big touchpoint for the most valuable class of new sources (authed
 * ones).
 *
 * Mirrors how `chipLabel` / `searchHint` / `iconName` are already carried
 * off the [SourcePluginDescriptor] rather than hand-wired per source.
 */
sealed interface SourceConfigField {
    /** Stable per-source field key, e.g. `"clientId"`. Persisted by the
     *  contributor and echoed back on writes; unique within a source. */
    val key: String

    /** Human-readable field label for the Settings row. */
    val label: String

    /** Optional one-line explanatory text rendered under the field.
     *  Empty string hides it. */
    val help: String

    /**
     * Validate a proposed raw value before it is persisted. Returns null
     * when the value is acceptable, or a short human-readable error
     * message to surface in the UI. The default accepts everything; a
     * blank value is always treated as "clear / reset to default" by the
     * contributor, so validators should return null for blank input.
     */
    fun validate(value: String): String? = null

    /** A plain, non-secret text field whose current value is shown and
     *  editable (e.g. a Notion database id). */
    data class PlainText(
        override val key: String,
        override val label: String,
        override val help: String = "",
        val placeholder: String = "",
    ) : SourceConfigField

    /** A write-only credential field. The stored value is never surfaced
     *  back to the UI (only a "configured" boolean is), and input is
     *  masked. Blank clears the stored credential. */
    data class SecretText(
        override val key: String,
        override val label: String,
        override val help: String = "",
        val placeholder: String = "",
    ) : SourceConfigField

    /** A URL field. Its current value is shown and editable; a non-blank
     *  value must be an `http(s)` URL. Blank resets to the source's baked
     *  default (e.g. the Prime Gaming feed URL). */
    data class UrlText(
        override val key: String,
        override val label: String,
        override val help: String = "",
        val placeholder: String = "",
    ) : SourceConfigField {
        override fun validate(value: String): String? {
            if (value.isBlank()) return null
            val ok = value.startsWith("http://") || value.startsWith("https://")
            return if (ok) null else "Enter an http(s) URL"
        }
    }

    /** A boolean behaviour toggle (e.g. "append top comments"). */
    data class Toggle(
        override val key: String,
        override val label: String,
        override val help: String = "",
    ) : SourceConfigField
}

/**
 * Current display value of a [SourceConfigField], streamed by the
 * contributor. The variant matches the field type:
 *  - [Text] — for [SourceConfigField.PlainText] / [SourceConfigField.UrlText]
 *    (carries the current value).
 *  - [Secret] — for [SourceConfigField.SecretText] (carries only whether a
 *    value is stored; never the value itself).
 *  - [Bool] — for [SourceConfigField.Toggle].
 */
sealed interface SourceConfigValue {
    data class Text(val value: String) : SourceConfigValue
    data class Secret(val configured: Boolean) : SourceConfigValue
    data class Bool(val on: Boolean) : SourceConfigValue
}

/**
 * Issue #1531 — a source's contribution of user-configurable fields to
 * the generic Settings config section.
 *
 * Bound `@IntoSet Set<SourceConfigContributor>` in `:app` (where the
 * `*ConfigImpl` backing stores already live, so a contributor can wrap
 * the existing DataStore / EncryptedSharedPreferences implementation
 * without leaking Android plumbing into the leaf source module). The
 * Settings layer consumes the whole set generically — no per-source UI
 * edit — the same way [SourcePluginRegistry] consumes descriptors.
 */
interface SourceConfigContributor {
    /** The owning source's stable id (matches its `@SourcePlugin` id).
     *  Used to route [set] calls back to this contributor. */
    val sourceId: String

    /** UI label for the section header, e.g. `"Reddit"`. */
    val displayName: String

    /** Optional one-line section-level explanation shown above the fields.
     *  Empty string hides it. */
    val sectionHelp: String get() = ""

    /** The typed fields this source declares, in render order. */
    fun fields(): List<SourceConfigField>

    /** Live map of `fieldKey → current value` for the declared [fields].
     *  Re-emits whenever any backing store changes. A field absent from
     *  the map renders with a type-appropriate empty default. */
    val values: Flow<Map<String, SourceConfigValue>>

    /**
     * Persist [raw] for [key]. Semantics are field-type dependent:
     *  - text / url / secret: a blank [raw] clears the value (reverting to
     *    the field's default where one exists);
     *  - toggle: [raw] is `"true"` / `"false"`.
     * Unknown keys are ignored.
     */
    suspend fun set(key: String, raw: String)
}
