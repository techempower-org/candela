package `in`.jphe.storyvox.feature.stats

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.repository.impact.ImpactShareData
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/** Hosted privacy policy, impact-sharing anchor (docs/privacy.md §2.9). */
private const val IMPACT_PRIVACY_URL = "https://candela.techempower.org/privacy/"

/**
 * Issue #1463 — opt-in **anonymous impact sharing** card on the Listening Stats
 * screen. The user taps to open a preview sheet showing the *exact* coarse payload
 * (byte-for-byte), then shares it through the Android share sheet to a destination of
 * their choosing. Nothing is ever sent automatically; the act of sharing is the consent.
 *
 * Visible only when there's a fresh contribution to share, or when the user already
 * shared this period (a quiet "thanks" state), so it never nags an empty history.
 */
@Composable
fun ImpactShareCard(
    data: ImpactShareData,
    onShared: () -> Unit,
    currentPeriod: String,
    modifier: Modifier = Modifier,
) {
    val alreadySharedThisPeriod = data.lastSharedPeriod == currentPeriod
    // Nothing to say: no fresh delta and not shared this period → render nothing.
    if (!data.hasSomethingToShare && !alreadySharedThisPeriod) return

    var showPreview by remember { mutableStateOf(false) }
    val spacing = LocalSpacing.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.impact_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.impact_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (data.lastSharedPeriod != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.impact_card_last_shared, data.lastSharedPeriod!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            if (data.hasSomethingToShare) {
                Button(
                    onClick = { showPreview = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.impact_card_button))
                }
            } else {
                // Shared this period, nothing new to add — a quiet acknowledgement.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.impact_card_all_shared),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    if (showPreview) {
        ImpactPreviewSheet(
            data = data,
            onDismiss = { showPreview = false },
            onConfirmShared = {
                showPreview = false
                onShared()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImpactPreviewSheet(
    data: ImpactShareData,
    onDismiss: () -> Unit,
    onConfirmShared: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val spacing = LocalSpacing.current
    val chooserTitle = stringResource(R.string.impact_share_chooser)
    val subject = stringResource(R.string.impact_share_subject, data.report.period)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = stringResource(R.string.impact_preview_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.impact_preview_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The exact bytes that will be shared — same string the share sheet sends.
            ExactPayloadBlock(payloadText = data.payloadText)

            ImpactSharingDisclosure()

            Text(
                text = stringResource(R.string.impact_preview_withdrawal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    shareImpactReport(
                        context = context,
                        text = data.payloadText,
                        subject = subject,
                        chooserTitle = chooserTitle,
                    )
                    onConfirmShared()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.impact_preview_share_button))
            }

            TextButton(
                onClick = { runCatching { uriHandler.openUri(IMPACT_PRIVACY_URL) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.impact_preview_privacy_link))
            }
        }
    }
}

/** Monospace, selectable block rendering the exact payload text byte-for-byte. */
@Composable
private fun ExactPayloadBlock(payloadText: String) {
    val spacing = LocalSpacing.current
    Column {
        Text(
            text = stringResource(R.string.impact_preview_payload_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = payloadText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(spacing.md),
            )
        }
    }
}

/**
 * The "What's shared / What's never shared" two-part disclosure. Shared by the preview
 * sheet and the Settings → About → Impact sharing explainer subscreen so the promise is
 * worded identically in both places.
 */
@Composable
fun ImpactSharingDisclosure(modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        DisclosureBlock(
            icon = Icons.Outlined.Share,
            heading = stringResource(R.string.impact_preview_shared_heading),
            lines = listOf(
                stringResource(R.string.impact_shared_line_totals),
                stringResource(R.string.impact_shared_line_sources),
                stringResource(R.string.impact_shared_line_meta),
            ),
        )
        DisclosureBlock(
            icon = Icons.Outlined.Lock,
            heading = stringResource(R.string.impact_preview_never_heading),
            lines = listOf(
                stringResource(R.string.impact_never_line_identity),
                stringResource(R.string.impact_never_line_device),
                stringResource(R.string.impact_never_line_content),
                stringResource(R.string.impact_never_line_traceback),
            ),
        )
    }
}

@Composable
private fun DisclosureBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    heading: String,
    lines: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = heading,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
        lines.forEach { line ->
            Text(
                text = "•  $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Open the system share sheet for the impact payload. Mirrors [shareStatsText] /
 * the reader's `shareQuoteText`: `ACTION_SEND` text/plain wrapped in a chooser,
 * `FLAG_ACTIVITY_NEW_TASK` for launch from a non-Activity Composable context. The
 * body ([text]) is exactly what the preview showed; [subject] helps email clients.
 */
private fun shareImpactReport(context: Context, text: String, subject: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
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
