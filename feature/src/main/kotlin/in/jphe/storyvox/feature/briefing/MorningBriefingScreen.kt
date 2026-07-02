package `in`.jphe.storyvox.feature.briefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * #1467 — Morning Briefing entry point (slice 1).
 *
 * Reached from the Settings hub. One button assembles today's briefing from the
 * default sources and starts playing it as a single continuous episode; the
 * assembled queue is shown with the current item highlighted. Because the queue
 * lives in the [BriefingQueueController][in.jphe.storyvox.playback.briefing]
 * singleton, playback keeps going (and advancing) when you leave this screen.
 *
 * Copy is inline for slice 1; source picker + string extraction + brass polish
 * are #1467 follow-ups.
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
    val spacing = LocalSpacing.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Morning Briefing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = "One continuous episode from your sources — Hacker News, arXiv, RSS, and GitHub. " +
                    "Play it hands-free on your walk.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = viewModel::buildAndPlay,
                enabled = !building,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (building) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Text("Building your briefing…")
                } else {
                    Text("Build & play my briefing")
                }
            }

            if (emptyResult) {
                Text(
                    text = "No items yet — make sure Hacker News, arXiv, RSS, or GitHub are " +
                        "enabled and have recent posts, then try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val current = session
            if (current != null) {
                Text(
                    text = if (current.finished) {
                        "Briefing complete · ${current.items.size} items"
                    } else {
                        "Playing ${current.position} of ${current.items.size}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    itemsIndexed(current.items) { index, item ->
                        BriefingItemRow(
                            title = item.title,
                            source = item.sourceId,
                            isCurrent = index == current.index && !current.finished,
                        )
                    }
                }
            }
        }
    }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
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
}
