package `in`.jphe.storyvox.feature.reader.script

import androidx.compose.ui.res.stringResource
import `in`.jphe.storyvox.feature.R
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
import androidx.compose.material3.FilterChip
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
import `in`.jphe.storyvox.data.db.entity.ScriptFormat
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
    val savedMessage = stringResource(R.string.scripts_saved)
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ScriptEditEvent.Saved -> snackbarHostState.showSnackbar(savedMessage)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNewDraft) R.string.scripts_new else R.string.scripts_edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.scripts_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::save, enabled = state.isDirty) {
                        Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.scripts_save))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.scripts_more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scripts_load_into_teleprompter)) },
                            leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                viewModel.loadIntoTeleprompter()
                                onOpenTeleprompter()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scripts_import_clipboard)) },
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
                label = { Text(stringResource(R.string.scripts_field_title)) },
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                ScriptFormat.entries.forEach { fmt ->
                    FilterChip(
                        selected = state.format == fmt.name,
                        onClick = { viewModel.onFormatChange(fmt) },
                        label = { Text(stringResource(fmt.labelRes())) },
                    )
                }
            }

            OutlinedTextField(
                value = state.tags,
                onValueChange = viewModel::onTagsChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.scripts_field_tags)) },
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
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.scripts_remove_tag))
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
                label = { Text(stringResource(R.string.scripts_field_body)) },
                placeholder = { Text(stringResource(R.string.scripts_body_placeholder)) },
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
    val total = remember(body) { TeleprompterScript.wordCount(body) }
    val spoken = remember(body) { TeleprompterScript.spokenWordCount(body) }
    val durationSecs = remember(body, wpm) { TeleprompterScript.estimateDurationSecs(body, wpm) }
    // When the script carries cues / headers / speaker labels, spoken < total —
    // show both so the user knows the duration counts only what's read aloud.
    val wordsLabel = if (spoken == total) {
        "$total words"
    } else {
        "$spoken spoken / $total total words"
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Text(
            text = "$wordsLabel · ${formatDuration(durationSecs)} at $wpm wpm",
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

/** Localized label for a [ScriptFormat] chip (#1819); the enum's own
 *  `label` stays as the English fallback for non-UI callers. */
internal fun ScriptFormat.labelRes(): Int = when (this) {
    ScriptFormat.FREEFORM -> R.string.scripts_format_freeform
    ScriptFormat.SHORT -> R.string.scripts_format_short
    ScriptFormat.FULL_SHOW -> R.string.scripts_format_full_show
}
