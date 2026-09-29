package `in`.jphe.storyvox.feature.reader.script

import `in`.jphe.storyvox.feature.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1366 — AI script writer bottom sheet. Opened from the teleprompter
 * transport's "Write a script" button; a lightweight sheet rather than a full
 * screen so it overlays the reader the user is already standing in.
 *
 * Topic field + length chips → Generate. Tokens stream into a preview as they
 * arrive; the finished script can be regenerated, hand-edited, then loaded
 * into the teleprompter. An unconfigured-provider error routes to Settings →
 * AI via [onOpenAiSettings] (the same nav the recap surface uses, #152).
 *
 * The ViewModel is screen-scoped (resolved from the reader's
 * `NavBackStackEntry`), so a freshly generated script survives an accidental
 * dismiss + reopen while the reader is up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptGeneratorSheet(
    onDismiss: () -> Unit,
    onOpenAiSettings: () -> Unit = {},
    viewModel: ScriptGeneratorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current

    var topic by rememberSaveable { mutableStateOf("") }
    var durationSecs by rememberSaveable {
        mutableStateOf(ScriptGeneratorViewModel.DEFAULT_DURATION_SECS)
    }
    // Inline edit of the finished script. `editText` seeds from the Done
    // script when Edit is tapped; committing writes it back through the VM.
    var editing by rememberSaveable { mutableStateOf(false) }
    var editText by rememberSaveable { mutableStateOf("") }

    val generating = state is ScriptGeneratorState.Generating

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.padding(top = spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.scripts_gen_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                stringResource(R.string.scripts_gen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text(stringResource(R.string.scripts_gen_topic)) },
                placeholder = { Text(stringResource(R.string.scripts_gen_topic_hint)) },
                enabled = !generating,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.scripts_gen_length),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                SCRIPT_DURATION_OPTIONS.forEach { secs ->
                    FilterChip(
                        selected = durationSecs == secs,
                        onClick = { durationSecs = secs },
                        enabled = !generating,
                        label = { Text("${secs}s") },
                    )
                }
            }

            BrassButton(
                label = when {
                    generating -> stringResource(R.string.scripts_gen_generating)
                    state is ScriptGeneratorState.Done -> stringResource(R.string.scripts_gen_regenerate)
                    else -> stringResource(R.string.scripts_gen_generate)
                },
                onClick = {
                    editing = false
                    viewModel.generateScript(topic, durationSecs)
                },
                variant = BrassButtonVariant.Primary,
                enabled = !generating && topic.isNotBlank(),
                loading = generating,
                modifier = Modifier.fillMaxWidth(),
            )

            when (val s = state) {
                is ScriptGeneratorState.Idle -> Unit

                is ScriptGeneratorState.Generating -> {
                    ScriptPreview(text = s.partial, placeholder = stringResource(R.string.scripts_gen_writing))
                    StatsRow(text = s.partial, viewModel = viewModel)
                    TextButton(
                        onClick = viewModel::stop,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.scripts_gen_stop))
                    }
                }

                is ScriptGeneratorState.Done -> {
                    if (editing) {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            label = { Text(stringResource(R.string.scripts_edit)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 280.dp),
                        )
                    } else {
                        ScriptPreview(text = s.script, placeholder = "")
                    }
                    StatsRow(text = if (editing) editText else s.script, viewModel = viewModel)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        if (editing) {
                            BrassButton(
                                label = stringResource(R.string.scripts_gen_done_editing),
                                onClick = {
                                    viewModel.setScript(editText.trim())
                                    editing = false
                                },
                                variant = BrassButtonVariant.Secondary,
                                enabled = editText.isNotBlank(),
                            )
                        } else {
                            BrassButton(
                                label = stringResource(R.string.scripts_gen_edit),
                                onClick = {
                                    editText = s.script
                                    editing = true
                                },
                                variant = BrassButtonVariant.Secondary,
                            )
                        }
                    }

                    BrassButton(
                        label = stringResource(R.string.scripts_load_into_teleprompter),
                        onClick = {
                            if (editing) {
                                viewModel.setScript(editText.trim())
                                editing = false
                            }
                            viewModel.loadIntoTeleprompter()
                            onDismiss()
                        },
                        variant = BrassButtonVariant.Primary,
                        enabled = (if (editing) editText else s.script).isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is ScriptGeneratorState.Error -> {
                    Text(
                        scriptGenerationFailureText(s.failure),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (s.routeToSettings) {
                        BrassButton(
                            label = stringResource(R.string.scripts_gen_open_ai_settings),
                            onClick = onOpenAiSettings,
                            variant = BrassButtonVariant.Secondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Read-only, scrollable rendering of the (streaming or finished) script,
 * capped so a long script scrolls inside the sheet instead of pushing the
 * action buttons off-screen.
 */
@Composable
private fun ScriptPreview(
    text: String,
    placeholder: String,
) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text.ifBlank { placeholder },
            style = MaterialTheme.typography.bodyLarge,
            color = if (text.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(spacing.md),
        )
    }
}

/** Word count + estimated spoken duration for [text]. */
@Composable
private fun StatsRow(
    text: String,
    viewModel: ScriptGeneratorViewModel,
) {
    val words = viewModel.wordCount(text)
    val secs = viewModel.estimatedDuration(text)
    Text(
        text = "$words words · ~${secs}s",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Localized text for a [ScriptGenerationFailure] (#1819). */
@Composable
private fun scriptGenerationFailureText(failure: ScriptGenerationFailure): String = when (failure) {
    ScriptGenerationFailure.NotConfigured -> stringResource(R.string.scripts_gen_err_not_configured)
    is ScriptGenerationFailure.AuthFailed -> stringResource(R.string.scripts_gen_err_auth, failure.provider)
    ScriptGenerationFailure.Transport -> stringResource(R.string.scripts_gen_err_transport)
    is ScriptGenerationFailure.Provider -> stringResource(R.string.scripts_gen_err_provider, failure.status)
    is ScriptGenerationFailure.Other -> failure.detail ?: stringResource(R.string.scripts_gen_err_generic)
}
