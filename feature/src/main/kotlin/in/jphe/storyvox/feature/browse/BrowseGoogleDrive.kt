package `in`.jphe.storyvox.feature.browse

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1534 / #1677 — the Google Drive chip's empty state, in its two
 * phases:
 *
 *  1. **Not connected** (or the session needs re-consent — [authRequired]):
 *     "Connect your Google Drive" → OAuth in a Custom Tab. Without an OAuth
 *     client id in this build the button hides and the copy says so.
 *  2. **Connected, nothing picked yet**: under `drive.file` Candela sees only
 *     what the user picks, so the CTA opens the Google Picker page. Without
 *     the Picker key the copy explains that instead of dangling a dead
 *     button. "Disconnect" is always offered once connected.
 */
@Composable
internal fun GoogleDriveEmptyState(
    connection: GoogleDriveConnectionUi,
    authRequired: Boolean,
    onConnect: () -> Unit,
    onPick: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val connectedPhase = connection.connected && !authRequired
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            modifier = Modifier.padding(horizontal = spacing.xl),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                stringResource(
                    if (connectedPhase) R.string.browse_gdrive_pick_title
                    else R.string.browse_gdrive_connect_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(
                    when {
                        connectedPhase && connection.pickerAvailable -> R.string.browse_gdrive_pick_body
                        connectedPhase -> R.string.browse_gdrive_pick_unavailable_body
                        connection.oauthAvailable -> R.string.browse_gdrive_connect_body
                        else -> R.string.browse_gdrive_unavailable_body
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            when {
                connectedPhase && connection.pickerAvailable -> {
                    Spacer(Modifier.height(spacing.md))
                    BrassButton(
                        label = stringResource(R.string.browse_gdrive_pick_button),
                        onClick = onPick,
                        variant = BrassButtonVariant.Primary,
                    )
                }
                !connectedPhase && connection.oauthAvailable -> {
                    Spacer(Modifier.height(spacing.md))
                    BrassButton(
                        label = stringResource(R.string.browse_gdrive_connect_button),
                        onClick = onConnect,
                        variant = BrassButtonVariant.Primary,
                    )
                }
            }
            if (connection.connected) {
                BrassButton(
                    label = stringResource(R.string.browse_gdrive_disconnect),
                    onClick = onDisconnect,
                    variant = BrassButtonVariant.Text,
                )
            }
        }
    }
}

/**
 * #1677 — FAB-launched manage dialog on a connected Drive chip that already
 * lists files: add more from Drive, or disconnect.
 */
@Composable
internal fun GoogleDriveManageDialog(
    pickerAvailable: Boolean,
    onPick: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browse_gdrive_manage_title)) },
        text = { Text(stringResource(R.string.browse_gdrive_manage_body)) },
        confirmButton = {
            if (pickerAvailable) {
                TextButton(onClick = { onDismiss(); onPick() }) {
                    Text(stringResource(R.string.browse_gdrive_pick_button))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(); onDisconnect() }) {
                Text(stringResource(R.string.browse_gdrive_disconnect))
            }
        },
    )
}

/** Open [url] in a Chrome Custom Tab (no-op on a blank URL). */
internal fun launchDriveCustomTab(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
}
