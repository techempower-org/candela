package `in`.jphe.storyvox.feature.reader.script

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.db.entity.TeleprompterScript
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1369 — the script editor. Title (single line) + body (multi-line,
 * fills the screen) + tags (comma-separated, with a removable-chip display) +
 * a live word-count/duration footer at the user's current teleprompter pace.
 * Save lives in the top bar; "Load into Teleprompter" and "Import from
 * clipboard" live in the overflow menu.
 *
 * @param onOpenTeleprompter navigate to the player after the editor content
 *   has been queued into the shared [TeleprompterScriptStore].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScriptEditScreen(
    onBack: () -> Unit,
    onOpenTeleprompter: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScriptEditViewModel = hiltViewModel(),
) {
    val spacing = LocalSpacing.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val wpm by viewModel.wpm.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ScriptEditEvent.Saved -> snackbarHostState.showSnackbar("Saved")
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNewDraft) "New script" else "Edit script") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::save, enabled = state.isDirty) {
                        Icon(Icons.Outlined.Check, contentDescription = "Save")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Load into Teleprompter") },
                            leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                viewModel.loadIntoTeleprompter()
                                onOpenTeleprompter()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Import from clipboard") },
                            leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                readClipboardText(context)?.let(viewModel::appendToBody)
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Title") },
            )

            OutlinedTextField(
                value = state.tags,
                onValueChange = viewModel::onTagsChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Tags (comma-separated)") },
            )

            val tagList = remember(state.tags) {
                state.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
            if (tagList.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    tagList.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = {
                                // Tap a chip to remove that tag from the field.
                                viewModel.onTagsChange(
                                    normalizeTags(tagList.filterNot { it == tag }.joinToString(", ")),
                                )
                            },
                            label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                Icon(Icons.Outlined.Close, contentDescription = "Remove tag")
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::onBodyChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("Script") },
                placeholder = { Text("Write or paste your script here…") },
            )

            ScriptMetricsFooter(
                body = state.body,
                wpm = wpm,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.xs),
            )
        }
    }
}

@Composable
private fun ScriptMetricsFooter(
    body: String,
    wpm: Int,
    modifier: Modifier = Modifier,
) {
    val words = remember(body) { TeleprompterScript.wordCount(body) }
    val durationSecs = remember(body, wpm) { TeleprompterScript.estimateDurationSecs(body, wpm) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Text(
            text = "$words words · ${formatDuration(durationSecs)} at $wpm wpm",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Read the current clipboard text, or null if empty/unavailable. Mirrors the
 *  platform-`ClipboardManager` read used elsewhere in the feature module
 *  (`AddByUrlSheet`, `FirstFictionPicker`) rather than the Compose clipboard
 *  API, for consistency. */
private fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() }
}
