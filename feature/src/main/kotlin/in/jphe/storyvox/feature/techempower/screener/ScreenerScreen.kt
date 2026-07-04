package `in`.jphe.storyvox.feature.techempower.screener

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1517 — the offline benefits screener surface.
 *
 * A single scrollable question form + bucketed results — deliberately a
 * **list**, not a spatial/overlay UI, so it is fully navigable by TalkBack
 * (invariant 4). Every program surface is read-aloud-able via the app's TTS
 * seam. UI chrome is bilingual through `res/values` + `res/values-es`; corpus
 * content carries its own EN/ES ([Localized]) and follows the device locale.
 *
 * The whole surface performs ZERO network calls — it reads a bundled asset and
 * evaluates locally — so it works in airplane mode and the "answers never leave
 * your device" promise is provable (invariant 1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenerScreen(
    onBack: () -> Unit,
    viewModel: ScreenerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    val spanish = locale.language == "es"

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.screener_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.screener_back),
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(scaffoldPadding))
            state.loadError -> ErrorState(Modifier.padding(scaffoldPadding))
            else -> ScreenerContent(
                state = state,
                spanish = spanish,
                onAnswerBool = viewModel::answerBool,
                onAnswerChoice = viewModel::answerChoice,
                onShowResults = viewModel::showResults,
                onReset = viewModel::reset,
                onReadAloud = viewModel::readAloud,
                onStopReadAloud = viewModel::stopReadAloud,
                modifier = Modifier.padding(scaffoldPadding),
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Box(
        modifier
            .fillMaxSize()
            .padding(spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.screener_load_error),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScreenerContent(
    state: ScreenerUiState,
    spanish: Boolean,
    onAnswerBool: (String, Boolean) -> Unit,
    onAnswerChoice: (String, String) -> Unit,
    onShowResults: () -> Unit,
    onReset: () -> Unit,
    onReadAloud: (String) -> Unit,
    onStopReadAloud: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corpus = state.corpus ?: return
    val spacing = LocalSpacing.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // Intro + offline reassurance.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    stringResource(R.string.screener_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.screener_offline_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Seed / sample-data banner — shown until the verified corpus lands.
        if (!corpus.metadata.isVerified) {
            item { SampleDataBanner() }
        }

        // Questions.
        items(corpus.questions, key = { it.id }) { question ->
            QuestionBlock(
                question = question,
                spanish = spanish,
                answer = state.answers[question.id],
                onAnswerBool = onAnswerBool,
                onAnswerChoice = onAnswerChoice,
            )
        }

        // Primary action + start-over.
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onShowResults,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.screener_show_results))
                }
                if (state.showResults || state.answers.isNotEmpty()) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.screener_start_over))
                    }
                }
            }
        }

        // Results.
        if (state.showResults) {
            item {
                ResultsHeader(
                    results = state.results,
                    spanish = spanish,
                    onReadAll = onReadAloud,
                    onStop = onStopReadAloud,
                )
            }
            if (state.results.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.screener_results_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.results, key = { it.program.id }) { result ->
                    ResultCard(
                        result = result,
                        spanish = spanish,
                        onReadAloud = onReadAloud,
                    )
                }
            }
        }

        // Verified-date footer — reads straight off the parsed corpus, so it
        // always matches the shipped JSON (acceptance criterion).
        item { VerifiedFooter(corpus = corpus) }
    }
}

@Composable
private fun SampleDataBanner() {
    val spacing = LocalSpacing.current
    val accent = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            .padding(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                stringResource(R.string.screener_seed_banner_title),
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.screener_seed_banner_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionBlock(
    question: ScreenerQuestion,
    spanish: Boolean,
    answer: Answer?,
    onAnswerBool: (String, Boolean) -> Unit,
    onAnswerChoice: (String, String) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            question.prompt.get(spanish),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            when (question.questionType) {
                QuestionType.BOOLEAN -> {
                    val current = (answer as? Answer.Bool)?.value
                    ChoicePill(
                        label = stringResource(R.string.screener_yes),
                        selected = current == true,
                        onClick = { onAnswerBool(question.id, true) },
                    )
                    ChoicePill(
                        label = stringResource(R.string.screener_no),
                        selected = current == false,
                        onClick = { onAnswerBool(question.id, false) },
                    )
                }
                QuestionType.SINGLE_SELECT -> {
                    val current = (answer as? Answer.Choice)?.optionId
                    question.options.forEach { option ->
                        ChoicePill(
                            label = option.label.get(spanish),
                            selected = current == option.id,
                            onClick = { onAnswerChoice(question.id, option.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun ResultsHeader(
    results: List<ScreenerResult>,
    spanish: Boolean,
    onReadAll: (String) -> Unit,
    onStop: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            stringResource(R.string.screener_results_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        if (results.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                OutlinedButton(onClick = { onReadAll(resultsAsSpeech(results, spanish)) }) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(spacing.xs))
                    Text(stringResource(R.string.screener_read_all))
                }
                TextButton(onClick = onStop) {
                    Text(stringResource(R.string.screener_stop))
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: ScreenerResult,
    spanish: Boolean,
    onReadAloud: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val program = result.program
    val bucketLabel = when (result.bucket) {
        EligibilityBucket.LIKELY -> stringResource(R.string.screener_bucket_likely)
        else -> stringResource(R.string.screener_bucket_maybe)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.5.dp, brass.copy(alpha = 0.55f), MaterialTheme.shapes.large)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            bucketLabel,
            style = MaterialTheme.typography.labelMedium,
            color = brass,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            program.name.get(spanish),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            program.summary.get(spanish),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(spacing.xs))
        FlowRowActions(
            program = program,
            spanish = spanish,
            onReadAloud = { onReadAloud(programAsSpeech(result, spanish)) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowActions(
    program: ScreenerProgram,
    spanish: Boolean,
    onReadAloud: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // Read-aloud — this IS an audiobook app.
        TextButton(onClick = onReadAloud) {
            Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(spacing.xs))
            Text(stringResource(R.string.screener_read_aloud))
        }
        // Call — only when a number is verified (never invented). Otherwise
        // route to 211, the public help line.
        val number = program.phone ?: "211"
        val callLabel = if (program.phone != null) {
            stringResource(R.string.screener_call)
        } else {
            stringResource(R.string.screener_call_211)
        }
        TextButton(
            onClick = { dial(context, number) },
            modifier = Modifier.semantics {
                contentDescription = "$callLabel $number"
            },
        ) {
            Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(spacing.xs))
            Text(callLabel)
        }
        // Learn more — opens the apply URL (Custom Tabs / browser when online).
        program.applyUrl?.let { url ->
            TextButton(onClick = { openUrl(context, url) }) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(spacing.xs))
                Text(stringResource(R.string.screener_learn_more))
            }
        }
    }
}

@Composable
private fun VerifiedFooter(corpus: ScreenerCorpus) {
    val spacing = LocalSpacing.current
    val date = corpus.metadata.verifiedDate
    val text = if (date != null) {
        stringResource(R.string.screener_verified_prefix, date)
    } else {
        stringResource(R.string.screener_verified_unknown)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.sm),
    )
}

// ─── Speech assembly (read-aloud text) ────────────────────────────────────

private fun programAsSpeech(result: ScreenerResult, spanish: Boolean): String =
    buildString {
        append(result.program.name.get(spanish))
        append(". ")
        append(result.program.summary.get(spanish))
    }

private fun resultsAsSpeech(results: List<ScreenerResult>, spanish: Boolean): String =
    results.joinToString(separator = "\n\n") { programAsSpeech(it, spanish) }

// ─── Intents ───────────────────────────────────────────────────────────────

private fun dial(context: android.content.Context, number: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    } catch (_: ActivityNotFoundException) {
        // No browser (offline-only device) — the phone number above is the
        // fallback path, so nothing more to do here.
    }
}
