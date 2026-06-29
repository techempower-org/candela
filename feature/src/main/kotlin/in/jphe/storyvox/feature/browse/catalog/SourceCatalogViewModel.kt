package `in`.jphe.storyvox.feature.browse.catalog

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Source Catalog (#1365) — the "browse backends like books" discovery
 * surface.
 *
 * With 25 registered sources the horizontal chip carousel in Browse is a
 * cramped discovery surface — it auto-scrolls to the selected card and
 * truncates everything else. This screen turns the catalog itself into
 * something worth exploring: every backend gets a rich card, grouped by
 * category, searchable by name + description.
 *
 * Data source is the same [SourcePluginRegistry] the Browse carousel and
 * the Settings → Plugins manager read — no parallel store. Enabled state
 * is projected off the `sourcePluginsEnabled` settings map exactly like
 * [PluginManagerViewModel][`in`.jphe.storyvox.feature.settings.plugins.PluginManagerViewModel],
 * with each descriptor's `defaultEnabled` as the fallback for ids the
 * user hasn't explicitly toggled.
 *
 * The screen owns the filter + grouping (pure functions [filterCatalog]
 * / [groupCatalog]) so the view-model stays a thin projection and the
 * transformation layer is unit-testable without a Compose host.
 */
@HiltViewModel
class SourceCatalogViewModel @Inject constructor(
    private val registry: SourcePluginRegistry,
    private val settings: SettingsRepositoryUi,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val uiState: StateFlow<SourceCatalogUiState> =
        combine(settings.settings, _query) { s, q ->
            val rows = registry.descriptors.map { descriptor ->
                val explicit = s.sourcePluginsEnabled[descriptor.id]
                SourceCatalogRow(
                    descriptor = descriptor,
                    enabled = explicit ?: descriptor.defaultEnabled,
                )
            }
            SourceCatalogUiState(rows = rows, query = q)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SourceCatalogUiState(),
        )

    fun setQuery(query: String) { _query.value = query }

    /** Enable/disable a source plugin in place — the catalog twin of the
     *  Plugin Manager's switch and the Browse carousel long-press "hide".
     *  Same single setter ([SettingsRepositoryUi.setSourcePluginEnabled]),
     *  so all three surfaces stay in lock-step. */
    fun toggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { settings.setSourcePluginEnabled(id, enabled) }
    }

    /**
     * Called when the user taps a card to *browse* that source.
     *
     * A disabled source must be enabled first: BrowseViewModel's
     * enabled-set guard bounces `_sourceId` to an enabled source whenever
     * the selected id isn't in the enabled set, and the carousel only
     * renders enabled descriptors — so navigating to Browse with a
     * disabled source selected would silently snap back to another
     * source and never show the one the user tapped. Enabling on choose
     * makes the source appear in the carousel AND loads its listing.
     *
     * No-op when the source is already enabled (the common case), so we
     * don't churn the settings store on every browse.
     */
    fun onSourceChosen(id: String) {
        val row = uiState.value.rows.firstOrNull { it.descriptor.id == id } ?: return
        if (!row.enabled) {
            viewModelScope.launch { settings.setSourcePluginEnabled(id, enabled = true) }
        }
    }
}

/** Source Catalog (#1365) — Compose-facing state: every registered
 *  source as a row (enabled flag resolved) plus the live search query.
 *  Filtering + category grouping happen in the screen via [filterCatalog]
 *  / [groupCatalog] so this stays a flat projection. */
@Immutable
data class SourceCatalogUiState(
    val rows: List<SourceCatalogRow> = emptyList(),
    val query: String = "",
) {
    /** Total registered source count — drives the "N sources to explore"
     *  header subtitle. Independent of the active search filter. */
    val totalCount: Int get() = rows.size
}

/** Source Catalog (#1365) — one source's descriptor joined with its
 *  resolved enabled state, mirroring `PluginManagerRow`. */
@Immutable
data class SourceCatalogRow(
    val descriptor: SourcePluginDescriptor,
    val enabled: Boolean,
)

/**
 * Source Catalog (#1365) — the four shelf-style groupings the catalog
 * renders, in display order. Distinct from the `:core-data`
 * [SourceCategory] enum: the catalog collapses Database into Other and
 * gives each group reader-facing copy + a short badge label.
 *
 * @property label Section-header title.
 * @property badge Compact label for the per-card category chip.
 */
enum class CatalogGroup(val label: String, val badge: String) {
    Books("Books & Literature", "Books"),
    Text("Web Fiction & Text", "Text"),
    Audio("Audio & Radio", "Audio"),
    Other("Other", "Other"),
}

/** Map a `:core-data` [SourceCategory] onto its catalog [CatalogGroup].
 *  Database folds into [CatalogGroup.Other] per the #1365 grouping. */
fun catalogGroupOf(category: SourceCategory): CatalogGroup = when (category) {
    SourceCategory.Ebook -> CatalogGroup.Books
    SourceCategory.Text -> CatalogGroup.Text
    SourceCategory.AudioStream -> CatalogGroup.Audio
    SourceCategory.Database, SourceCategory.Other -> CatalogGroup.Other
}

/** Source Catalog (#1365) — one rendered category section: the group and
 *  the rows that fell into it, input order preserved (registry display
 *  order). */
@Immutable
data class SourceCatalogSection(
    val group: CatalogGroup,
    val rows: List<SourceCatalogRow>,
)

/**
 * Source Catalog (#1365) — substring filter across displayName,
 * description, and id (case-insensitive), matching the Plugin Manager's
 * `filterPlugins` semantics so "wiki" finds Wikipedia + Wikisource and
 * "radio" finds the Radio source via its description. Blank query keeps
 * everything.
 */
fun filterCatalog(rows: List<SourceCatalogRow>, query: String): List<SourceCatalogRow> {
    if (query.isBlank()) return rows
    val needle = query.trim().lowercase()
    return rows.filter { row ->
        row.descriptor.displayName.lowercase().contains(needle) ||
            row.descriptor.description.lowercase().contains(needle) ||
            row.descriptor.id.lowercase().contains(needle)
    }
}

/**
 * Source Catalog (#1365) — bucket rows into [CatalogGroup] sections in
 * enum-declared order (Books → Text → Audio → Other), dropping empty
 * sections so a filtered search doesn't render bare headers. Row order
 * within each section is preserved from the input (the registry's stable
 * display order).
 */
fun groupCatalog(rows: List<SourceCatalogRow>): List<SourceCatalogSection> =
    CatalogGroup.entries.mapNotNull { group ->
        val inGroup = rows.filter { catalogGroupOf(it.descriptor.category) == group }
        if (inGroup.isEmpty()) null else SourceCatalogSection(group, inGroup)
    }
