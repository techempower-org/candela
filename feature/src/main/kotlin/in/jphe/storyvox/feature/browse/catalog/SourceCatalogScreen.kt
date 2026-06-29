package `in`.jphe.storyvox.feature.browse.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.component.TestTags
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Source Catalog (#1365) — Browse → "Source Library".
 *
 * A full-screen, shelf-style browser for all 25 registered backends.
 * Reached from the entry-point card below the Browse carousel. Sources
 * are grouped into four category sections (Books & Literature, Web
 * Fiction & Text, Audio & Radio, Other), each rendered as a rich
 * [SourceCatalogCard] with an icon, description, category badge, and an
 * inline enable switch.
 *
 * Two ways out:
 *  - [onNavigateBack] — the top-bar back arrow / OS back.
 *  - [onSelectSource] — tapping a card's body; the host wires this to
 *    return the chosen source id to the Browse screen and pop, so Browse
 *    lands on that source. [SourceCatalogViewModel.onSourceChosen] runs
 *    first to enable a disabled source so it actually shows up.
 *
 * @param onSelectSource invoked with the chosen source id. The screen
 *  has already called [SourceCatalogViewModel.onSourceChosen] for it.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SourceCatalogScreen(
    onNavigateBack: () -> Unit,
    onSelectSource: (String) -> Unit,
    viewModel: SourceCatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    // Filter + group are pure functions; recompute only when their inputs
    // change rather than on every recomposition.
    val sections = remember(state.rows, state.query) {
        groupCatalog(filterCatalog(state.rows, state.query))
    }
    val matchCount = remember(sections) { sections.sumOf { it.rows.size } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Source Library",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Browse",
                        )
                    }
                },
            )
        },
    ) { padding ->
        // #1333 pattern — surface descendant testTags as resource-ids so
        // on-device uiautomator/adb can target catalog elements by id.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .semantics { testTagsAsResourceId = true },
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TestTags.SourceCatalogList),
                contentPadding = PaddingValues(
                    start = spacing.md,
                    end = spacing.md,
                    top = spacing.sm,
                    bottom = spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                // Hero subtitle — "N sources to explore". The top-bar
                // already carries the title; this is the inviting count.
                item("header") {
                    Text(
                        text = "${state.totalCount} sources to explore",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = spacing.xs),
                    )
                }

                // Search across name + description.
                item("search") {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        label = { Text("Search sources") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(TestTags.BrowseSearchField),
                    )
                }

                if (sections.isEmpty()) {
                    item("empty") {
                        EmptyCatalogState(query = state.query)
                    }
                } else {
                    sections.forEach { section ->
                        item(key = "header-${section.group.name}") {
                            SourceCatalogSectionHeader(
                                group = section.group,
                                count = section.rows.size,
                            )
                        }
                        items(
                            items = section.rows,
                            key = { it.descriptor.id },
                        ) { row ->
                            SourceCatalogCard(
                                row = row,
                                onOpen = {
                                    // Enable-if-disabled first, then hand the
                                    // id back to the host to drive Browse.
                                    viewModel.onSourceChosen(row.descriptor.id)
                                    onSelectSource(row.descriptor.id)
                                },
                                onToggle = { enabled ->
                                    viewModel.toggleEnabled(row.descriptor.id, enabled)
                                },
                            )
                        }
                    }

                    // Footer count line — mirrors the active-filter result
                    // count so a long search still tells the user how many
                    // matched.
                    item("footer") {
                        Text(
                            text = if (state.query.isBlank()) {
                                "Tap a source to browse it · toggle to add it to your shelf"
                            } else {
                                "$matchCount of ${state.totalCount} sources match “${state.query.trim()}”"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = spacing.md),
                        )
                    }
                }
            }
        }
    }
}

/** Source Catalog (#1365) — empty state when a search matches nothing. */
@Composable
private fun EmptyCatalogState(query: String) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xxl, horizontal = spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Filled.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "No sources match",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Nothing in the catalog matches “${query.trim()}”. Try a different name — or clear the search to see every source.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
