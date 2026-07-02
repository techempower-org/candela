package `in`.jphe.storyvox.data.source.plugin

/**
 * Plugin-seam Phase 1 (#384) — marker annotation that auto-registers a
 * `FictionSource` implementation into [SourcePluginRegistry].
 *
 * Decorate a `FictionSource` implementation with this annotation, and
 * the `:core-plugin-ksp` SymbolProcessor generates a Hilt module that
 * contributes a [SourcePluginDescriptor] for it into a multibinding
 * `Set<SourcePluginDescriptor>`. The registry consumes that set at
 * construction time, surfacing the plugin without the caller having to
 * touch the central `BrowseSourceKey` enum / settings DataStore /
 * Settings → Library & Sync screen by hand (issue #384's "~17
 * touchpoints per new backend" → "~4" goal).
 *
 * Targets a class (the `FictionSource` implementation itself). KSP
 * emits the descriptor binding from the annotation's metadata + the
 * @Inject constructor on the target class.
 *
 * Retention is BINARY so the annotation is visible to the KSP pass at
 * compile time but isn't carried into the runtime classpath — there's
 * no need to read it via reflection once KSP has emitted the bindings.
 *
 * ## What KSP generates (#1371)
 *
 * As of the touchpoint-consolidation pass (#1371) the `:core-plugin-ksp`
 * processor emits **both** Hilt contributions a `FictionSource` needs
 * from this single annotation:
 *
 *  1. `@Provides @IntoSet SourcePluginDescriptor` — the registry / UI
 *     surface (`SourcePluginRegistry`, Browse chip row, Settings
 *     auto-section).
 *  2. `@Binds @IntoMap @StringKey(id) FictionSource` — the repository's
 *     `Map<String, FictionSource>` routing entry.
 *
 * So a new backend no longer hand-writes a `@Binds @IntoMap @StringKey`
 * module: annotate the `FictionSource` and both bindings appear.
 * Hand-written `@IntoMap` bindings remain only for *aliases* (a second
 * id pointing at the same source, e.g. the `kvmr` → Radio migration
 * alias) and for *non-`FictionSource`* contributions (`AuthSource`,
 * `SessionHydrator`), which the annotation does not model.
 *
 * @property id Stable identifier matching `FictionSource.id` and the
 *  legacy `SourceIds` constant. Used as the key in
 *  `UiSettings.sourcePluginsEnabled` and in any persisted
 *  per-plugin state.
 * @property displayName Human-readable label for the Browse chip and
 *  the Settings → Library & Sync row.
 * @property defaultEnabled Whether a fresh install renders the plugin
 *  with its toggle ON. The settings migration uses this to seed
 *  the per-plugin map on first read.
 * @property category Grouping hint for the Settings auto-section
 *  (`Text` / `AudioStream` / `Database` / etc.).
 * @property supportsFollow True when the plugin implements a
 *  non-trivial `setFollowed` / `followsList`. The Browse and Detail
 *  UIs read this to gate Follow controls.
 * @property supportsSearch True when the plugin implements a
 *  meaningful `search()` surface. Browse hides the Search tab when
 *  false.
 * @property description One-line plain-text subtitle surfaced in the
 *  plugin manager card (#404). Roughly "<surface> · <auth posture>"
 *  — e.g. "Web fiction · Atom feeds + EPUB downloads". Empty string
 *  hides the subtitle and renders the card more compactly.
 * @property sourceUrl Canonical "where this plugin reads from" URL
 *  surfaced in the plugin manager details sheet (#404). Used so a
 *  user can verify what storyvox is actually talking to. Empty
 *  string hides the row.
 * @property chipLabel Short label for the Browse chip strip (#1371),
 *  where [displayName]'s formal form ("Archive of Our Own") is too
 *  long for a narrow-phone chip. Empty string falls back to
 *  [displayName]. Read by `BrowseSourceUi.chipLabel` from the
 *  descriptor, replacing a per-source `when`-branch.
 * @property searchHint Subtitle for the Browse search empty-state
 *  (#1371, #271) — e.g. "Search AO3 by tag, fandom, or character".
 *  Empty string falls back to "Search <displayName>". Read by
 *  `BrowseSourceUi.searchHint` from the descriptor.
 * @property iconName Material icon name for the Browse source glyph
 *  (#1371) — e.g. "MenuBook", "RssFeed", matching an
 *  `androidx.compose.material.icons.Icons.Filled.*` entry. Empty
 *  string falls back to the per-source `when`-branch in
 *  `BrowseSourceCarousel`. Carried on the descriptor so an
 *  out-of-tree backend can declare its glyph without a feature-module
 *  edit.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class SourcePlugin(
    val id: String,
    val displayName: String,
    val defaultEnabled: Boolean = false,
    val category: SourceCategory = SourceCategory.Text,
    val supportsFollow: Boolean = false,
    val supportsSearch: Boolean = false,
    val description: String = "",
    val sourceUrl: String = "",
    val chipLabel: String = "",
    val searchHint: String = "",
    val iconName: String = "",
)

/**
 * High-level grouping for the Settings → Library & Sync auto-section
 * (#384). Categories drive section headings and the order plugins
 * appear in. Adding a category here is a one-line change; backends
 * use the existing values via the [SourcePlugin] annotation.
 *
 * - [Text] — HTML / Markdown / plain-text fiction: Royal Road, AO3,
 *   GitHub, RSS, Outline, Wikipedia, Notion.
 * - [Ebook] — EPUB-based catalogs: Project Gutenberg, Standard Ebooks,
 *   local EPUB files.
 * - [AudioStream] — live audio that bypasses TTS: KVMR community
 *   radio, future LibriVox MP3s, etc.
 * - [Database] — structured-DB backends that don't fit Text (Notion's
 *   alternative classification if we later split it; reserved).
 * - [Other] — escape hatch for backends that don't fit the above.
 */
enum class SourceCategory {
    Text,
    Ebook,
    AudioStream,
    Database,
    Other,
}
