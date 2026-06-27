package `in`.jphe.storyvox.feature.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #786 — brass-tinted strip surfaced above network grids when
 * [ConnectivityState.Offline][`in`.jphe.storyvox.data.network.ConnectivityState].
 *
 * Mirrors the `Ao3SignInBanner` idiom (Card + `surfaceVariant`, the project's
 * brass-warm M3 palette) so it reads as part of the same family of inline
 * status banners. The retry CTA is optional: Browse wires it to a re-fetch,
 * Library passes `null` (there's nothing to retry until the user taps a cover).
 *
 * A11y: the banner is a [LiveRegionMode.Polite] live region so TalkBack
 * announces the offline message when it appears (and again if it re-appears
 * after reconnecting) — without a tap, focus never moves here, so composition
 * entry alone does not reliably announce. Polite (not Assertive) so it queues
 * behind whatever the user is currently hearing instead of interrupting.
 */
@Composable
fun OfflineBanner(
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = spacing.md,
                vertical = spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.offline_banner_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                BrassButton(
                    label = stringResource(R.string.offline_banner_retry),
                    onClick = onRetry,
                    variant = BrassButtonVariant.Text,
                )
            }
        }
    }
}
