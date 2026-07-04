package `in`.jphe.storyvox.feature.techempower.decoder

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import `in`.jphe.storyvox.data.TechEmpowerLinks
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1516 — benefits letter decoder surface.
 *
 * Photograph (or type) a Notice of Action → detect its form number → show a
 * hand-written **verified** plain-language explainer (EN/ES), listenable via
 * TTS. Unknown form → honest fallback (read-aloud + call 211), never a guess.
 *
 * A plain scrolling form (not a spatial/overlay UI) so it's fully TalkBack-
 * navigable (invariant 4). Chrome is bilingual via `res/values` + `res/values-es`;
 * explainer content carries its own EN/ES and follows the device locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecoderScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    viewModel: DecoderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val spanish = LocalConfiguration.current.locales[0].language == "es"

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.decoder_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.decoder_back),
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        when {
            state.isLoading -> CenteredBox(Modifier.padding(scaffoldPadding)) { CircularProgressIndicator() }
            state.loadError -> CenteredBox(Modifier.padding(scaffoldPadding)) {
                Text(
                    stringResource(R.string.decoder_load_error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> DecoderContent(
                state = state,
                spanish = spanish,
                onScan = onScan,
                onInputChange = viewModel::onInputChange,
                onDecode = viewModel::decode,
                onUseLastScan = viewModel::useMostRecentScan,
                onReadAloud = viewModel::readAloud,
                onStop = viewModel::stopReadAloud,
                onReset = viewModel::reset,
                modifier = Modifier.padding(scaffoldPadding),
            )
        }
    }
}

@Composable
private fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecoderContent(
    state: DecoderUiState,
    spanish: Boolean,
    onScan: () -> Unit,
    onInputChange: (String) -> Unit,
    onDecode: () -> Unit,
    onUseLastScan: () -> Unit,
    onReadAloud: (String) -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corpus = state.corpus ?: return
    val spacing = LocalSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                stringResource(R.string.decoder_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.decoder_offline_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!corpus.metadata.isVerified) SampleDataBanner()

        // Capture / reuse-scan affordances.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedButton(onClick = onScan) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.xs))
                Text(stringResource(R.string.decoder_scan))
            }
            if (state.hasRecentScan) {
                TextButton(onClick = onUseLastScan) {
                    Text(stringResource(R.string.decoder_use_last_scan))
                }
            }
        }

        // Manual entry (paste text or a form number).
        OutlinedTextField(
            value = state.inputText,
            onValueChange = onInputChange,
            label = { Text(stringResource(R.string.decoder_input_label)) },
            placeholder = { Text(stringResource(R.string.decoder_input_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Button(onClick = onDecode, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.decoder_decode))
            }
            if (state.result != null || state.inputText.isNotEmpty()) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.decoder_start_over))
                }
            }
        }

        when (val result = state.result) {
            is DecodeResult.Known -> KnownExplainerCard(
                explainer = result.explainer,
                spanish = spanish,
                onReadAloud = onReadAloud,
                onStop = onStop,
            )
            is DecodeResult.Unknown -> UnknownFallbackCard(
                scannedText = result.scannedText,
                onReadAloud = onReadAloud,
                onStop = onStop,
            )
            null -> Unit
        }

        VerifiedFooter(corpus)
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
        Icon(Icons.Filled.Info, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                stringResource(R.string.decoder_seed_banner_title),
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.decoder_seed_banner_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnownExplainerCard(
    explainer: NoticeExplainer,
    spanish: Boolean,
    onReadAloud: (String) -> Unit,
    onStop: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val brass = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.5.dp, brass.copy(alpha = 0.55f), MaterialTheme.shapes.large)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            explainer.title.get(spanish),
            style = MaterialTheme.typography.titleMedium,
            color = brass,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        ExplainerSection(stringResource(R.string.decoder_section_meaning), explainer.whatItMeans.get(spanish))
        ExplainerSection(stringResource(R.string.decoder_section_why), explainer.whyYouGotIt.get(spanish))
        ExplainerSection(stringResource(R.string.decoder_section_todo), explainer.whatToDo.get(spanish))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedButton(onClick = { onReadAloud(explainerAsSpeech(explainer, spanish)) }) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.xs))
                Text(stringResource(R.string.decoder_read_aloud))
            }
            TextButton(onClick = onStop) { Text(stringResource(R.string.decoder_stop)) }
            val number = explainer.phone ?: TechEmpowerLinks.PRIMARY_HELP_NUMBER
            TextButton(onClick = { dial(context, number) }) {
                Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.xs))
                Text(if (explainer.phone != null) stringResource(R.string.decoder_call) else stringResource(R.string.decoder_call_211))
            }
        }

        explainer.verifiedAt?.let {
            Text(
                stringResource(R.string.decoder_verified_prefix, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } ?: Text(
            stringResource(R.string.decoder_verified_unknown),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExplainerSection(label: String, body: String) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnknownFallbackCard(
    scannedText: String,
    onReadAloud: (String) -> Unit,
    onStop: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.tertiary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.5.dp, accent.copy(alpha = 0.5f), MaterialTheme.shapes.large)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            stringResource(R.string.decoder_unknown_title),
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.decoder_unknown_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            // Read-aloud the scanned text (OCR → TTS) — no interpretation, just
            // the user's own letter read back to them.
            if (scannedText.isNotBlank()) {
                OutlinedButton(onClick = { onReadAloud(scannedText) }) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(spacing.xs))
                    Text(stringResource(R.string.decoder_read_scanned))
                }
                TextButton(onClick = onStop) { Text(stringResource(R.string.decoder_stop)) }
            }
            TextButton(onClick = { dial(context, TechEmpowerLinks.PRIMARY_HELP_NUMBER) }) {
                Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.xs))
                Text(stringResource(R.string.decoder_call_211))
            }
            TextButton(onClick = { openUrl(context, TechEmpowerLinks.WEBSITE_URL) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.xs))
                Text(stringResource(R.string.decoder_learn_more))
            }
        }
    }
}

@Composable
private fun VerifiedFooter(corpus: ExplainerCorpus) {
    val spacing = LocalSpacing.current
    val date = corpus.metadata.verifiedDate
    val text = if (date != null) {
        stringResource(R.string.decoder_verified_prefix, date)
    } else {
        stringResource(R.string.decoder_verified_unknown)
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

// ─── Speech + intents ──────────────────────────────────────────────────────

private fun explainerAsSpeech(e: NoticeExplainer, spanish: Boolean): String =
    buildString {
        append(e.title.get(spanish)); append(". ")
        append(e.whatItMeans.get(spanish)); append(" ")
        append(e.whyYouGotIt.get(spanish)); append(" ")
        append(e.whatToDo.get(spanish))
    }

private fun dial(context: Context, number: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse(TechEmpowerLinks.telUri(number)))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    } catch (_: ActivityNotFoundException) {
        // No browser available (offline-only device) — the call affordance
        // above remains the actionable fallback.
    }
}
