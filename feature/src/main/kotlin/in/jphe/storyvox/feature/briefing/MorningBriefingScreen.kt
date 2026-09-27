package `in`.jphe.storyvox.feature.briefing

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.briefing.BriefingPlanner
import `in`.jphe.storyvox.data.briefing.BriefingSources
import `in`.jphe.storyvox.data.briefing.SourceQuota
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * #1467 — Morning Briefing: pick sources, play them back to back as one
 * episode, optionally have it prepared every morning.
 *
 * Reached from the Settings hub. The picker writes through
 * [BriefingViewModel] to the briefing DataStore; the queue lives in the
 * [BriefingQueueController][in.jphe.storyvox.playback.briefing] singleton, so
 * playback keeps going (and advancing) when you leave this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningBriefingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BriefingViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val building by viewModel.building.collectAsStateWithLifecycle()
    val emptyResult by viewModel.emptyResult.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val readyToday by viewModel.readyToday.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.briefing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.briefing_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Text(
                    text = stringResource(R.string.briefing_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                PlayButton(building = building, ready = readyToday != null, onClick = viewModel::buildAndPlay)
            }

            readyToday?.let { ready ->
                item {
                    Text(
                        text = stringResource(
                            R.string.briefing_ready_today,
                            formatClock(Instant.ofEpochMilli(ready.builtAtMillis).atZone(ZoneId.systemDefault()).toLocalTime()),
                            ready.items.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (emptyResult) {
                item {
                    Text(
                        text = stringResource(R.string.briefing_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            val current = session
            if (current != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (current.finished) {
                                stringResource(R.string.briefing_complete, current.items.size)
                            } else {
                                stringResource(R.string.briefing_playing, current.position, current.items.size)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (!current.finished) {
                            TextButton(onClick = viewModel::stop) { Text(stringResource(R.string.briefing_stop)) }
                        }
                    }
                }
                itemsIndexed(current.items, key = { i, it -> "q$i:${it.chapterId}" }) { index, item ->
                    BriefingItemRow(
                        title = item.title,
                        source = sourceLabel(item.sourceId),
                        isCurrent = index == current.index && !current.finished,
                    )
                }
            }

            item { SectionHeader(R.string.briefing_sources_header, R.string.briefing_sources_hint) }

            itemsIndexed(settings.config.sources, key = { _, q -> "src:${q.sourceId}" }) { _, quota ->
                SourceRow(
                    quota = quota,
                    label = sourceLabel(quota.sourceId),
                    onToggle = { viewModel.setSourceEnabled(quota.sourceId, it) },
                    onCount = { viewModel.setSourceCount(quota.sourceId, it) },
                )
            }

            item { SectionHeader(R.string.briefing_schedule_header, R.string.briefing_schedule_hint) }

            item {
                val schedule = settings.schedule
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    ToggleRow(
                        label = stringResource(R.string.briefing_schedule_toggle),
                        checked = schedule.enabled,
                        onToggle = viewModel::setScheduleEnabled,
                    )
                    if (schedule.enabled) {
                        OutlinedButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    R.string.briefing_schedule_time,
                                    formatClock(LocalTime.of(schedule.hour, schedule.minute)),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        ScheduleTimeDialog(
            hour = settings.schedule.hour,
            minute = settings.schedule.minute,
            onDismiss = { showTimePicker = false },
            onConfirm = { h, m ->
                showTimePicker = false
                viewModel.setScheduleTime(h, m)
            },
        )
    }
}

@Composable
private fun PlayButton(building: Boolean, ready: Boolean, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    Button(onClick = onClick, enabled = !building, modifier = Modifier.fillMaxWidth()) {
        if (building) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(spacing.sm))
            Text(stringResource(R.string.briefing_building))
        } else {
            Text(stringResource(if (ready) R.string.briefing_play_ready else R.string.briefing_play))
        }
    }
}

@Composable
private fun SectionHeader(@StringRes title: Int, @StringRes hint: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = LocalSpacing.current.md)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onToggle(!checked) }
            .padding(vertical = LocalSpacing.current.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // Row owns the click (one TalkBack target); the Switch mirrors state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SourceRow(
    quota: SourceQuota,
    label: String,
    onToggle: (Boolean) -> Unit,
    onCount: (Int) -> Unit,
) {
    Column {
        ToggleRow(label = label, checked = quota.enabled, onToggle = onToggle)
        if (quota.enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onCount(quota.count - 1) },
                    enabled = quota.count > BriefingPlanner.MIN_COUNT,
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.briefing_count_decrease, label))
                }
                Text(
                    text = stringResource(R.string.briefing_count_label, quota.count),
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(
                    onClick = { onCount(quota.count + 1) },
                    enabled = quota.count < BriefingPlanner.MAX_COUNT,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.briefing_count_increase, label))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimeDialog(
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val is24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = is24h)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.briefing_schedule_pick_time)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text(stringResource(R.string.briefing_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.briefing_cancel)) }
        },
    )
}

@Composable
private fun BriefingItemRow(
    title: String,
    source: String,
    isCurrent: Boolean,
) {
    val spacing = LocalSpacing.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
            )
            Text(
                text = source,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Localized label for a briefing source id; unknown ids fall back to the raw id. */
@Composable
private fun sourceLabel(sourceId: String): String {
    val res = when (sourceId) {
        SourceIds.GOOGLE_NEWS -> R.string.briefing_source_googlenews
        SourceIds.HACKERNEWS -> R.string.briefing_source_hackernews
        SourceIds.ARXIV -> R.string.briefing_source_arxiv
        SourceIds.RSS -> R.string.briefing_source_rss
        SourceIds.GITHUB -> R.string.briefing_source_github
        BriefingSources.INBOX -> R.string.briefing_source_inbox
        BriefingSources.CALENDAR -> R.string.briefing_source_calendar
        else -> null
    }
    return res?.let { stringResource(it) } ?: sourceId
}

@Composable
private fun formatClock(time: LocalTime): String {
    val formatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    return time.format(formatter)
}
