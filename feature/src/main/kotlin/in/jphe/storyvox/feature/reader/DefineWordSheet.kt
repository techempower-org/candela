package `in`.jphe.storyvox.feature.reader

import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.dictionary.DictionaryEntry
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1230 — the reader's tap-to-define popup. Long-pressing a word in
 * [ReaderTextView] runs a lookup through [ReaderViewModel.defineWord]; this
 * brass-tinted [ModalBottomSheet] renders the resulting [DictionaryUiState].
 *
 * Every non-[DictionaryUiState.Hidden] state still surfaces two fallbacks so a
 * lookup is never a dead end:
 *  - **Open in dictionary app** — Android's [Intent.ACTION_DEFINE] (the offline
 *    path the issue calls for), with an [Intent.ACTION_WEB_SEARCH] backstop and
 *    a toast if the device has neither.
 *  - **Ask AI** — hands a prebuilt "what does X mean?" question to [onAskAi],
 *    which the reader routes to the chat surface (preserving the older #188
 *    character-lookup affordance this sheet supersedes).
 *
 * The sheet is presentational: all lookup state is hoisted to the ViewModel, so
 * a config change re-renders from the collected [DictionaryUiState] rather than
 * re-fetching.
 */
@Composable
fun DefineWordSheet(
    state: DictionaryUiState,
    onDismiss: () -> Unit,
    /** Invoked with a prebuilt, localized "what does X mean?" question to seed
     *  the chat surface. */
    onAskAi: (question: String) -> Unit,
    onRetry: (word: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is DictionaryUiState.Hidden) return
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val word = when (state) {
        is DictionaryUiState.Hidden -> return
        is DictionaryUiState.Loading -> state.word
        is DictionaryUiState.Loaded -> state.definition.word
        is DictionaryUiState.Empty -> state.word
        is DictionaryUiState.Error -> state.word
    }
    val askAiQuestion = stringResource(R.string.reader_define_ask_ai_question, word)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
        ) {
            // Headword + the open-book sigil. The word is the title; the
            // pronunciation (when the source carries it) sits just beneath.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.AutoStories,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.size(spacing.sm))
                Text(
                    text = word,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            (state as? DictionaryUiState.Loaded)?.definition?.pronunciation?.let { ipa ->
                Text(
                    text = ipa,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp, top = spacing.xxs),
                )
            }

            Spacer(Modifier.height(spacing.md))

            when (state) {
                is DictionaryUiState.Hidden -> Unit
                is DictionaryUiState.Loading -> LoadingRow(word)
                is DictionaryUiState.Loaded -> DefinitionBody(state.definition.entries)
                is DictionaryUiState.Empty -> Text(
                    text = stringResource(R.string.reader_define_empty, word),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is DictionaryUiState.Error -> ErrorBody(
                    message = state.message,
                    onRetry = { onRetry(word) },
                )
            }

            Spacer(Modifier.height(spacing.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(spacing.sm))

            // Fallback actions — always present, so a missing entry or a dropped
            // network still leaves the listener a way to look the word up.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextButton(onClick = { launchWordLookup(context, word) }) {
                    Icon(
                        imageVector = Icons.Outlined.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(spacing.xs))
                    Text(stringResource(R.string.reader_define_system_dict))
                }
                TextButton(onClick = { onAskAi(askAiQuestion) }) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(spacing.xs))
                    Text(stringResource(R.string.reader_ask_ai))
                }
            }

            // Attribution only when we actually showed Wiktionary content.
            if (state is DictionaryUiState.Loaded) {
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = stringResource(R.string.reader_define_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadingRow(word: String) {
    val spacing = LocalSpacing.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.size(spacing.sm))
        Text(
            text = stringResource(R.string.reader_define_loading, word),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DefinitionBody(entries: List<DictionaryEntry>) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        entries.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                if (entry.partOfSpeech.isNotBlank()) {
                    Text(
                        text = entry.partOfSpeech,
                        style = MaterialTheme.typography.labelLarge,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                entry.senses.forEachIndexed { index, sense ->
                    Row {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(22.dp),
                        )
                        Text(
                            text = sense,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = stringResource(R.string.reader_define_error),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (message.isNotBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.reader_define_retry))
        }
    }
}

/**
 * Issue #1230 — the offline / system fallback. Tries Android's dictionary
 * intent ([Intent.ACTION_DEFINE], passing the word via [Intent.EXTRA_TEXT] per
 * the platform contract), then a web search, then a toast — so the action does
 * *something* useful on every device, with or without a dictionary app.
 */
private fun launchWordLookup(context: Context, word: String) {
    val define = Intent(Intent.ACTION_DEFINE).putExtra(Intent.EXTRA_TEXT, word)
    if (tryStart(context, define)) return

    val search = Intent(Intent.ACTION_WEB_SEARCH)
        .putExtra(SearchManager.QUERY, "define $word")
    if (tryStart(context, search)) return

    Toast.makeText(
        context,
        context.getString(R.string.reader_define_no_dictionary_app),
        Toast.LENGTH_SHORT,
    ).show()
}

private fun tryStart(context: Context, intent: Intent): Boolean = try {
    // #1265 — a non-Activity context (e.g. the application context) can't
    // launch an Activity without FLAG_ACTIVITY_NEW_TASK. In Compose
    // LocalContext is normally the Activity, but guard anyway. Catch every
    // exception (not just ActivityNotFoundException) so a SecurityException or
    // an odd OEM throw from a dictionary/search app can't crash the reader —
    // we just fall through to the next candidate / the no-app toast.
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
} catch (e: Exception) {
    false
}
