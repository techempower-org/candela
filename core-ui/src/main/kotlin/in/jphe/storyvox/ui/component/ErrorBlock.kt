package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/** Where to render an [ErrorBlock]. */
enum class ErrorPlacement {
    /**
     * Full-screen, centered. Use when the screen has no other content
     * (first-load failure with no cached data).
     */
    FullScreen,

    /**
     * Compact card pinned at the top of a scrollable surface. Use when
     * cached data is already on screen and the error is a tail/refresh
     * blip — don't blank out what the user is reading.
     */
    Banner,
}

/**
 * Brass-themed "the realm is unreachable" treatment for fetch / refresh
 * failures. Mirrors the visual rhythm of [FollowsScreen.SignedOutEmpty]
 * so the realm aesthetic stays coherent across negative paths.
 *
 * @param title short headline, e.g. "The realm is unreachable"
 * @param message body copy explaining what happened in plain English
 * @param onRetry primary action; pass null to omit the retry button
 *   (e.g. when the only retry path is to leave and re-enter the screen)
 * @param retryLabel primary button label, defaults to "Try again"
 * @param onBack optional secondary action — usually a Back nav. Issue
 *   #169 fix: a full-screen error must never be a dead-end; if the
 *   call site can't usefully retry (`onRetry = null`), it should at
 *   least pass `onBack` so the user has a way out other than the OS
 *   back gesture. Banner placement ignores `onBack` (the surrounding
 *   screen still has its own nav).
 * @param backLabel secondary button label, defaults to "Back"
 * @param placement [ErrorPlacement.FullScreen] or [ErrorPlacement.Banner]
 */
@Composable
fun ErrorBlock(
    title: String,
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    retryLabel: String = "Try again",
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    placement: ErrorPlacement = ErrorPlacement.FullScreen,
) {
    val spacing = LocalSpacing.current
    when (placement) {
        ErrorPlacement.FullScreen -> Column(
            modifier = modifier.fillMaxSize().padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MagicSkeletonTile(
                modifier = Modifier.size(width = 160.dp, height = 220.dp),
                shape = MaterialTheme.shapes.medium,
                glyphSize = 80.dp,
            )
            Spacer(Modifier.height(spacing.lg))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null || onBack != null) {
                Spacer(Modifier.height(spacing.lg))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    if (onBack != null) {
                        BrassButton(
                            label = backLabel,
                            onClick = onBack,
                            variant = BrassButtonVariant.Secondary,
                        )
                    }
                    if (onRetry != null) {
                        BrassButton(
                            label = retryLabel,
                            onClick = onRetry,
                            variant = BrassButtonVariant.Primary,
                        )
                    }
                }
            }
        }

        ErrorPlacement.Banner -> Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.xs)
                // #1160: the banner is inserted above existing content on a
                // refresh/append failure — focus doesn't move, so without a
                // live region "Couldn't refresh" is never spoken. Assertive
                // because it reports a failure the user should hear promptly.
                .semantics { liveRegion = LiveRegionMode.Assertive },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onRetry != null) {
                    BrassButton(
                        label = retryLabel,
                        onClick = onRetry,
                        variant = BrassButtonVariant.Text,
                    )
                }
            }
        }
    }
}

// region Previews

@Preview(name = "FullScreen — RR offline (dark)", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewErrorBlockFullScreenDark() = LibraryNocturneTheme(darkTheme = true) {
    ErrorBlock(
        title = "The realm is unreachable",
        message = "We couldn't reach Royal Road. Check your connection and try again.",
        onRetry = {},
        placement = ErrorPlacement.FullScreen,
    )
}

@Preview(name = "FullScreen — RR offline (light)", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewErrorBlockFullScreenLight() = LibraryNocturneTheme(darkTheme = false) {
    ErrorBlock(
        title = "The realm is unreachable",
        message = "We couldn't reach Royal Road. Check your connection and try again.",
        onRetry = {},
        placement = ErrorPlacement.FullScreen,
    )
}

@Preview(name = "FullScreen — no retry button", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewErrorBlockFullScreenNoRetry() = LibraryNocturneTheme(darkTheme = true) {
    ErrorBlock(
        title = "Couldn't load this fiction",
        message = "We couldn't reach Royal Road. Go back and try again in a moment.",
        onRetry = null,
        placement = ErrorPlacement.FullScreen,
    )
}

@Preview(name = "Banner — over Hero (dark)", widthDp = 360)
@Composable
private fun PreviewErrorBlockBannerDark() = LibraryNocturneTheme(darkTheme = true) {
    ErrorBlock(
        title = "Couldn't refresh",
        message = "Showing cached data.",
        onRetry = {},
        placement = ErrorPlacement.Banner,
    )
}

@Preview(name = "Banner — over Hero (light)", widthDp = 360)
@Composable
private fun PreviewErrorBlockBannerLight() = LibraryNocturneTheme(darkTheme = false) {
    ErrorBlock(
        title = "Couldn't refresh",
        message = "Showing cached data.",
        onRetry = {},
        placement = ErrorPlacement.Banner,
    )
}

@Preview(name = "Banner — long message", widthDp = 360)
@Composable
private fun PreviewErrorBlockBannerLong() = LibraryNocturneTheme(darkTheme = true) {
    ErrorBlock(
        title = "Couldn't refresh",
        message = "Royal Road returned a Cloudflare challenge that we couldn't solve in the background — your cached chapters still play, but new chapters won't appear until next refresh.",
        onRetry = {},
        placement = ErrorPlacement.Banner,
    )
}

// endregion

