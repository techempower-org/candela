package `in`.jphe.storyvox.feature.reader.script

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.db.entity.TeleprompterScript
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Issue #1369 — the saved-scripts list. Search bar, a feed of script cards
 * (title · duration badge · tags · last-edited), swipe-to-delete with undo, a
 * long-press context menu (Duplicate / Delete / Load into Teleprompter), and a
 * FAB to author a new one. Tapping a card opens the editor.
 *
 * @param onOpenScript open the editor for the given script id. The FAB passes
 *   a freshly-minted UUID (the editor starts blank; first save inserts it).
 * @param onOpenTeleprompter navigate to the player after a script has been
 *   queued into the shared [TeleprompterScriptStore].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ScriptListScreen(
    onBack: () -> Unit,
    onOpenScript: (String) -> Unit,
    onOpenTeleprompter: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScriptManagerViewModel = hiltViewModel(),
) {
    val spacing = LocalSpacing.current
    val query by viewModel.query.collectAsStateWithLifecycle()
    val scripts by viewModel.scripts.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Delete + "Undo" snackbar, shared by the swipe gesture and the long-press
    // context menu so both paths behave identically.
    val deleteWithUndo: (TeleprompterScript) -> Unit = { script ->
        viewModel.deleteWithUndo(script)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Deleted \"${script.title.ifBlank { "Untitled" }}\"",
                actionLabel = "Undo",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Scripts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenScript(UUID.randomUUID().toString()) }) {
                Icon(Icons.Outlined.Add, contentDescription = "New script")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                },
                placeholder = { Text("Search scripts") },
            )

            if (scripts.isEmpty()) {
                ScriptsEmptyState(
                    isSearching = query.isNotBlank(),
                    query = query,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = spacing.md,
                        end = spacing.md,
                        top = spacing.xs,
                        // Clear the FAB so the last row isn't trapped beneath it.
                        bottom = spacing.xxxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    items(scripts, key = { it.id }) { script ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value != SwipeToDismissBoxValue.Settled) {
                                    deleteWithUndo(script)
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = { SwipeDeleteBackground() },
                        ) {
                            ScriptRow(
                                script = script,
                                onOpen = { onOpenScript(script.id) },
                                onDuplicate = { viewModel.duplicate(script) },
                                onDelete = { deleteWithUndo(script) },
                                onLoadIntoTeleprompter = {
                                    viewModel.loadIntoTeleprompter(script)
                                    onOpenTeleprompter()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ScriptRow(
    script: TeleprompterScript,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onLoadIntoTeleprompter: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var menuOpen by remember { mutableStateOf(false) }
    // Spoken word count (cues/headers/labels excluded) — matches the
    // spoken-based duration badge. Remembered so a 2,500-word show script
    // isn't re-tokenized on every recomposition.
    val spokenWords = remember(script.body) { TeleprompterScript.spokenWordCount(script.body) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            ),
    ) {
        Box {
            Column(modifier = Modifier.padding(spacing.md)) {
                Text(
                    text = script.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = spacing.xxs),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatDuration(script.estimatedDurationSecs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$spokenWords words",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            script.updatedAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        ).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val tagList = remember(script.tags) {
                    script.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
                if (tagList.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
                    ) {
                        // Cap the inline chip row; the editor shows them all.
                        tagList.take(4).forEach { tag ->
                            SuggestionChip(
                                onClick = onOpen,
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Load into Teleprompter") },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onLoadIntoTeleprompter()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDuplicate()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun SwipeDeleteBackground() {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeleteGlyph()
            DeleteGlyph()
        }
    }
}

@Composable
private fun DeleteGlyph() {
    Icon(
        Icons.Outlined.Delete,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun ScriptsEmptyState(
    isSearching: Boolean,
    query: String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = if (isSearching) {
                "No scripts match \"$query\"."
            } else {
                "No scripts yet.\nTap + to write one, or generate one with AI."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(spacing.xl),
        )
    }
}

/** Format whole seconds as `M:SS`, or `H:MM:SS` past an hour. */
internal fun formatDuration(totalSecs: Int): String {
    val s = totalSecs.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
