package `in`.jphe.storyvox.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.repository.stats.DayActivity
import `in`.jphe.storyvox.data.repository.stats.SourceShare
import `in`.jphe.storyvox.data.repository.stats.TimeOfDayBucket

/**
 * Issue #1235 — hand-drawn charts for the listening-stats dashboard.
 * No external charting dependency (the issue calls this out): the
 * weekly trend and the source breakdown are drawn with Compose
 * [Canvas], and the time-of-day rows are plain clipped boxes. All
 * colours come from the active [MaterialTheme] so light/dark both work.
 */

/** Vertical bar chart of finished chapters per day, oldest → newest. */
@Composable
fun WeeklyActivityChart(
    days: List<DayActivity>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (days.isEmpty()) return
    val maxChapters = (days.maxOfOrNull { it.finishedChapters } ?: 0).coerceAtLeast(1)
    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp),
        ) {
            val slot = size.width / days.size
            val barWidth = slot * 0.46f
            val gap = (slot - barWidth) / 2f
            val radius = CornerRadius(6f, 6f)
            val minVisible = 3.dp.toPx() // a non-zero day always shows a sliver
            days.forEachIndexed { i, day ->
                val left = i * slot + gap
                // Track (full-height faint pill) for every day.
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = radius,
                )
                if (day.finishedChapters > 0) {
                    val frac = day.finishedChapters.toFloat() / maxChapters
                    val barHeight = (size.height * frac).coerceAtLeast(minVisible)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(left, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = radius,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            days.forEach { day ->
                Text(
                    text = StatsFormatting.weekdayInitial(day.date.dayOfWeek.value),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
        }
    }
}

/**
 * Donut of finished-chapter share per source. Sweep is proportional to
 * each slice's [SourceShare.finishedChapters]; [sliceColors] is indexed
 * positionally so the donut and the legend agree on colour.
 */
@Composable
fun SourceDonut(
    shares: List<SourceShare>,
    sliceColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    if (shares.isEmpty() || sliceColors.isEmpty()) return
    val total = shares.sumOf { it.finishedChapters }.coerceAtLeast(1)
    Canvas(modifier.size(156.dp)) {
        val strokePx = 30.dp.toPx()
        val diameter = size.minDimension - strokePx
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Butt)
        var startAngle = -90f
        shares.forEachIndexed { i, share ->
            val sweep = 360f * (share.finishedChapters.toFloat() / total)
            drawArc(
                color = sliceColors[i % sliceColors.size],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            startAngle += sweep
        }
    }
}

/** A coloured swatch + label + trailing value, for the donut legend. */
@Composable
fun LegendRow(
    color: Color,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One labelled horizontal bar for the time-of-day histogram. */
@Composable
fun TimeOfDayRow(
    bucket: TimeOfDayBucket,
    maxCount: Int,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.secondary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val frac = if (maxCount <= 0) 0f else bucket.finishedChapters.toFloat() / maxCount
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = StatsFormatting.dayPartLabel(bucket.part),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(88.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(trackColor),
        ) {
            if (frac > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(frac.coerceIn(0f, 1f))
                        .height(14.dp)
                        .background(barColor),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = bucket.finishedChapters.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}
