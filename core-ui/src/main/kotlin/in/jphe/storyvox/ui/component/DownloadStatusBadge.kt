package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Issue #1461 — small badge inside a [ChapterCard] showing a chapter's
 * in-progress / failed body-download status. Sibling of [ChapterCacheBadge]
 * (PCM cache) but a different axis: this tracks the text/HTML download that
 * bulk "Download all chapters" and EAGER auto-download drive.
 *
 * Four visual states keyed off [ChapterDownloadBadge]:
 *  - [ChapterDownloadBadge.None]: renders nothing — zero footprint inside the
 *    parent `Row(spacedBy)`, so a not-downloading chapter looks identical to
 *    before #1461.
 *  - [ChapterDownloadBadge.Queued]: [Icons.Outlined.Schedule], muted
 *    `onSurfaceVariant` tint — "waiting its turn (or for Wi-Fi)".
 *  - [ChapterDownloadBadge.Downloading]: a small indeterminate
 *    [CircularProgressIndicator], primary (brass) tint — "fetching now".
 *  - [ChapterDownloadBadge.Failed]: [Icons.Outlined.ErrorOutline], `error`
 *    tint — "this chapter didn't download".
 *
 * The terminal *Downloaded* state is NOT shown here — [ChapterCard] renders the
 * OfflineBolt icon for `isDownloaded`, so the two never appear at once.
 *
 * Material Icons + Material3 primitives only. Accessibility mirrors
 * [ChapterCacheBadge]: each state sets a `contentDescription` on the Icon slot
 * AND via a `semantics` block, and [ChapterCard] appends the same clause to the
 * merged row description.
 */
@Composable
fun DownloadStatusBadge(
    badge: ChapterDownloadBadge,
    modifier: Modifier = Modifier,
) {
    when (badge) {
        // Render nothing — parent Row's spacedBy won't budget space for an
        // absent composable, so a not-downloading row keeps its prior width.
        ChapterDownloadBadge.None -> Unit

        ChapterDownloadBadge.Queued -> {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = "Download queued",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier
                    .size(20.dp)
                    .semantics { contentDescription = "Download queued" },
            )
        }

        ChapterDownloadBadge.Downloading -> {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = modifier
                    .size(18.dp)
                    .semantics { contentDescription = "Downloading" },
            )
        }

        ChapterDownloadBadge.Failed -> {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = "Download failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier
                    .size(20.dp)
                    .semantics { contentDescription = "Download failed" },
            )
        }
    }
}
