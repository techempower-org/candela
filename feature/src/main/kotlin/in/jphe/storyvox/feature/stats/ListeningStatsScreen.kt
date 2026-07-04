package `in`.jphe.storyvox.feature.stats

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.repository.stats.ListeningStats
import `in`.jphe.storyvox.data.repository.stats.SourceShare
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1235 — "Listening Stats" dashboard.
 *
 * Reached from the Settings hub. Card-based layout in the app's brass
 * surface idiom (`surfaceContainerHigh` + `shapes.large`, mirroring
 * `SettingsGroupCard` / the Library resume card). All time figures are
 * estimates derived from finished-chapter durations — every one is
 * prefixed "≈" and a footnote explains why (see `ListeningStatsDao`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningStatsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ListeningStatsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // #1463 — opt-in anonymous impact share payload for the loaded snapshot.
    val impact by viewModel.impact.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appName = stringResource(R.string.stats_app_name)
    val chooserTitle = stringResource(R.string.stats_share_chooser)

    val shareableStats = (uiState as? ListeningStatsUiState.Loaded)
        ?.stats
        ?.takeIf { it.hasData }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.stats_title),
                        style = MaterialTheme.typography.titleMedium,
                        // #1136 idiom — heading() so TalkBack heading-nav lands here.
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.stats_back),
                        )
                    }
                },
                actions = {
                    if (shareableStats != null) {
                        // #1265 — resolve every share line from string resources
                        // here (plurals for the book/streak counts); the formatter
                        // stays free of hardcoded English and JVM-testable.
                        val shareContent = ShareSummaryContent(
                            header = stringResource(R.string.stats_share_header, appName),
                            timeListened = stringResource(
                                R.string.stats_share_time,
                                StatsFormatting.formatDuration(shareableStats.totalEstimatedMs),
                            ),
                            booksFinished = pluralStringResource(
                                R.plurals.stats_share_books_finished,
                                shareableStats.booksCompleted,
                                shareableStats.booksCompleted,
                            ),
                            chaptersRead = stringResource(
                                R.string.stats_share_chapters,
                                StatsFormatting.formatCompactNumber(shareableStats.chaptersFinished.toLong()),
                            ),
                            dayStreak = pluralStringResource(
                                R.plurals.stats_share_streak,
                                shareableStats.currentStreakDays,
                                shareableStats.currentStreakDays,
                            ),
                        )
                        IconButton(
                            onClick = {
                                shareStatsText(
                                    context = context,
                                    text = StatsFormatting.shareSummary(shareableStats, shareContent),
                                    chooserTitle = chooserTitle,
                                )
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.stats_share),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val state = uiState) {
            is ListeningStatsUiState.Loading ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

            is ListeningStatsUiState.Loaded ->
                if (state.stats.hasData) {
                    StatsBody(
                        stats = state.stats,
                        impact = impact,
                        onShared = viewModel::onShared,
                        contentPadding = padding,
                    )
                } else {
                    EmptyStats(contentPadding = padding)
                }
        }
    }
}

@Composable
private fun StatsBody(
    stats: ListeningStats,
    impact: `in`.jphe.storyvox.data.repository.impact.ImpactShareData?,
    onShared: () -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        HeroCard(stats)
        StatTileGrid(stats)
        WeeklyCard(stats)
        if (stats.perSource.isNotEmpty()) SourceCard(stats)
        TimeOfDayCard(stats)
        // #1463 — opt-in anonymous impact sharing, in context beneath the very
        // numbers it would (coarsely, anonymously) contribute to.
        if (impact != null) {
            ImpactShareCard(
                data = impact,
                onShared = onShared,
                currentPeriod = impact.report.period,
            )
        }
        Text(
            text = stringResource(R.string.stats_estimate_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HeroCard(stats: ListeningStats) {
    StatsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.stats_total_time_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "≈ " + StatsFormatting.formatDuration(stats.totalEstimatedMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (stats.currentStreakDays > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    R.string.stats_streak_line,
                    stats.currentStreakDays,
                    stats.longestStreakDays,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StatTileGrid(stats: ListeningStats) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            StatTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.AutoStories,
                value = StatsFormatting.formatCompactNumber(stats.booksCompleted.toLong()),
                label = stringResource(R.string.stats_books_finished),
            )
            StatTile(
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                value = StatsFormatting.formatCompactNumber(stats.chaptersFinished.toLong()),
                label = stringResource(R.string.stats_chapters_read),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            StatTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Notes,
                value = StatsFormatting.formatCompactNumber(stats.wordsRead),
                label = stringResource(R.string.stats_words_read),
            )
            StatTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Bookmark,
                value = StatsFormatting.formatCompactNumber(stats.booksStarted.toLong()),
                label = stringResource(R.string.stats_books_started),
            )
        }
    }
}

@Composable
private fun WeeklyCard(stats: ListeningStats) {
    StatsCard {
        SectionTitle(stringResource(R.string.stats_this_week_title))
        Spacer(Modifier.height(12.dp))
        WeeklyActivityChart(days = stats.weeklyActivity)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            InlineMetric(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_today),
                value = "≈ " + StatsFormatting.formatDuration(stats.todayEstimatedMs),
            )
            InlineMetric(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.stats_last_seven_days),
                value = "≈ " + StatsFormatting.formatDuration(stats.weekEstimatedMs),
            )
        }
    }
}

@Composable
private fun SourceCard(stats: ListeningStats) {
    val displayShares = collapseSources(stats.perSource)
    val palette = donutPalette()
    StatsCard {
        SectionTitle(stringResource(R.string.stats_by_source_title))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceDonut(
                shares = displayShares,
                sliceColors = palette,
            )
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                displayShares.forEachIndexed { i, share ->
                    LegendRow(
                        color = palette[i % palette.size],
                        label = sourceLabel(share),
                        value = StatsFormatting.formatCompactNumber(share.finishedChapters.toLong()),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeOfDayCard(stats: ListeningStats) {
    val maxCount = stats.timeOfDay.maxOfOrNull { it.finishedChapters } ?: 0
    StatsCard {
        SectionTitle(stringResource(R.string.stats_time_of_day_title))
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stats.timeOfDay.forEach { bucket ->
                TimeOfDayRow(bucket = bucket, maxCount = maxCount)
            }
        }
    }
}

// ── Small shared pieces ────────────────────────────────────────────────

/** Brass group card matching the Settings / Library surface idiom. */
@Composable
private fun StatsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(LocalSpacing.current.md)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(LocalSpacing.current.md)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InlineMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyStats(contentPadding: androidx.compose.foundation.layout.PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(LocalSpacing.current.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.AutoStories,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.stats_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.stats_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Pure-ish helpers used only by this screen ──────────────────────────

/** Theme-derived, visually-distinct palette for the donut + legend. */
@Composable
private fun donutPalette(): List<Color> {
    val cs = MaterialTheme.colorScheme
    return listOf(
        cs.primary,
        cs.tertiary,
        cs.secondary,
        cs.primary.copy(alpha = 0.55f),
        cs.tertiary.copy(alpha = 0.55f),
        cs.onSurfaceVariant,
    )
}

@Composable
private fun sourceLabel(share: SourceShare): String =
    if (share.sourceId == OTHER_SOURCE_ID) {
        stringResource(R.string.stats_other_sources)
    } else {
        StatsFormatting.sourceDisplayName(share.sourceId)
    }

/**
 * Collapse a long source list to the top [MAX_DONUT_SLICES] - 1 sources
 * plus a single combined "Other" slice, so the donut stays readable. A
 * list at or under the cap passes through unchanged.
 */
internal fun collapseSources(shares: List<SourceShare>): List<SourceShare> {
    if (shares.size <= MAX_DONUT_SLICES) return shares
    val head = shares.take(MAX_DONUT_SLICES - 1)
    val tail = shares.drop(MAX_DONUT_SLICES - 1)
    val other = SourceShare(
        sourceId = OTHER_SOURCE_ID,
        finishedChapters = tail.sumOf { it.finishedChapters },
        estimatedMs = tail.sumOf { it.estimatedMs },
    )
    return head + other
}

internal const val MAX_DONUT_SLICES = 6
internal const val OTHER_SOURCE_ID = "__other__"

/**
 * Open the system share sheet for a plain-text stats summary. Mirrors
 * the reader's `shareQuoteText` (issue #1234): `ACTION_SEND` wrapped in
 * a chooser, `FLAG_ACTIVITY_NEW_TASK` so it launches from a Composable's
 * non-Activity context.
 */
private fun shareStatsText(context: Context, text: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
