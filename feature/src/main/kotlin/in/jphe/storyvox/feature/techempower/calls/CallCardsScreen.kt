package `in`.jphe.storyvox.feature.techempower.calls

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import `in`.jphe.storyvox.data.TechEmpowerLinks
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1518 — the "make the call" surface.
 *
 * Per-program call cards: tap-to-dial (`ACTION_DIAL`, no CALL_PHONE permission,
 * no call log), best time to call, a short script of what to say, a checklist
 * of what to ask, and a post-call answer-capture sheet. Cards are listenable —
 * rehearse the call in the car (invariant 4). A card with no verified number
 * routes through 211 (we never invent a number).
 *
 * On returning to the app after a dial, the capture sheet is offered — detected
 * via ON_RESUME, with NO call-log access. Chrome is bilingual via `res/values`
 * + `res/values-es`; card content carries its own EN/ES and follows the locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallCardsScreen(
    onBack: () -> Unit,
    viewModel: CallCardsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val spanish = LocalConfiguration.current.locales[0].language == "es"

    // Foreground-return → offer the capture sheet (no call-log access).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onReturnedToForeground() }

    val selected = state.selectedCard

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        selected?.title?.get(spanish) ?: stringResource(R.string.calls_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) viewModel.backToList() else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.calls_back),
                        )
                    }
                },
            )
        },
    ) { pad ->
        when {
            state.isLoading -> Centered(Modifier.padding(pad)) { CircularProgressIndicator() }
            state.loadError -> Centered(Modifier.padding(pad)) {
                Text(
                    stringResource(R.string.calls_load_error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            selected != null -> CardDetail(
                card = selected,
                spanish = spanish,
                onDialInitiated = viewModel::onDialInitiated,
                onOpenCapture = viewModel::openCapture,
                onReadAloud = viewModel::readAloud,
                onStop = viewModel::stopReadAloud,
                modifier = Modifier.padding(pad),
            )
            else -> CardList(
                corpus = state.corpus,
                spanish = spanish,
                onSelect = viewModel::select,
                modifier = Modifier.padding(pad),
            )
        }
    }

    if (state.showCapture && selected != null) {
        CaptureSheet(
            card = selected,
            spanish = spanish,
            values = state.captureByCard[selected.id].orEmpty(),
            onValueChange = { fieldId, value -> viewModel.updateCapture(selected.id, fieldId, value) },
            onDismiss = viewModel::dismissCapture,
        )
    }
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun CardList(
    corpus: CallCardsCorpus?,
    spanish: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    corpus ?: return
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Text(
                stringResource(R.string.calls_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!corpus.metadata.isVerified) item { SampleDataBanner() }
        items(corpus.cards, key = { it.id }) { card ->
            CallCardRow(card = card, spanish = spanish, onClick = { onSelect(card.id) })
        }
    }
}

@Composable
private fun CallCardRow(card: CallCard, spanish: Boolean, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.5.dp, brass.copy(alpha = 0.55f), MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(Icons.Filled.Phone, contentDescription = null, tint = brass, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                card.title.get(spanish),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            card.org?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = brass)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardDetail(
    card: CallCard,
    spanish: Boolean,
    onDialInitiated: () -> Unit,
    onOpenCapture: () -> Unit,
    onReadAloud: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val brass = MaterialTheme.colorScheme.primary
    val number = card.dialNumber()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        card.bestTimeToCall?.let {
            Section(stringResource(R.string.calls_best_time), it.get(spanish))
        }

        // What to say — the script.
        if (card.whatToSay.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                SectionHeading(stringResource(R.string.calls_what_to_say))
                card.whatToSay.forEach { line ->
                    Text("• ${line.get(spanish)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // What to ask — the checklist.
        if (card.whatToAsk.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                SectionHeading(stringResource(R.string.calls_what_to_ask))
                card.whatToAsk.forEach { item ->
                    Text("☐ ${item.get(spanish)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Actions.
        Button(
            onClick = {
                dial(context, number)
                onDialInitiated()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(spacing.xs))
            Text(
                if (card.phone != null) {
                    stringResource(R.string.calls_call) + "  " + number
                } else {
                    stringResource(R.string.calls_call_via_211)
                },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedButton(onClick = { onReadAloud(cardAsSpeech(card, spanish)) }) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.xs))
                Text(stringResource(R.string.calls_read_aloud))
            }
            TextButton(onClick = onStop) { Text(stringResource(R.string.calls_stop)) }
            TextButton(onClick = onOpenCapture) { Text(stringResource(R.string.calls_add_notes)) }
        }

        // Verified-date.
        val date = card.verifiedDate
        Text(
            if (date != null) stringResource(R.string.calls_verified_prefix, date)
            else stringResource(R.string.calls_verified_unknown),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureSheet(
    card: CallCard,
    spanish: Boolean,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md)
                .padding(bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                stringResource(R.string.calls_capture_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.calls_capture_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            card.captureFields.forEach { field ->
                OutlinedTextField(
                    value = values[field.id].orEmpty(),
                    onValueChange = { onValueChange(field.id, it) },
                    label = { Text(field.label.get(spanish)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = field.id != "notes",
                )
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.calls_capture_save))
            }
        }
    }
}

@Composable
private fun Section(label: String, body: String) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        SectionHeading(label)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionHeading(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
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
                stringResource(R.string.calls_seed_banner_title),
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.calls_seed_banner_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Speech + intents ──────────────────────────────────────────────────────

private fun cardAsSpeech(card: CallCard, spanish: Boolean): String = buildString {
    append(card.title.get(spanish)); append(". ")
    card.bestTimeToCall?.let { append(it.get(spanish)); append(" ") }
    if (card.whatToSay.isNotEmpty()) {
        card.whatToSay.forEach { append(it.get(spanish)); append(" ") }
    }
    if (card.whatToAsk.isNotEmpty()) {
        card.whatToAsk.forEach { append(it.get(spanish)); append(" ") }
    }
}

private fun dial(context: Context, number: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse(TechEmpowerLinks.telUri(number)))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }
}
