package `in`.jphe.storyvox.feature.browse

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlinx.coroutines.launch

/**
 * Issue #1507 — Notion connect / manage bottom sheet, opened by the FAB
 * and the empty-state CTA on Browse → Notion. Mirrors [BrowseRssManageSheet]
 * so the two in-Browse add-affordances feel like one family.
 *
 * Three postures:
 *  - **Connected**: shows the workspace name, an "Add more pages"
 *    re-consent (re-launches OAuth to grant more objects; OAuth builds
 *    only), and Disconnect.
 *  - **Not connected, OAuth available**: a primary "Connect Notion" that
 *    launches consent in a Chrome Custom Tab, plus the advanced paste path.
 *  - **Not connected, no OAuth creds in this build**: only the advanced
 *    paste path (token + optional database id), always expanded.
 *
 * Successful connect (OAuth completes in MainActivity, or a token is
 * pasted+saved here) auto-enables the Notion (PAT) source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseNotionManageSheet(
    viewModel: BrowseViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current
    val conn by viewModel.notionConnection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var draftToken by remember { mutableStateOf("") }
    var draftDbId by remember { mutableStateOf("") }
    var advancedExpanded by remember { mutableStateOf(false) }

    // Show the paste fields only when NOT connected. With no OAuth creds in
    // this build the paste path is the only way, so show it inline;
    // otherwise it hides behind the "Advanced" toggle.
    val showTokenFields = !conn.connected && (advancedExpanded || !conn.oauthAvailable)

    fun launchConsent() {
        scope.launch {
            val url = viewModel.beginNotionOAuth()
            if (url != null) openInCustomTab(context, url)
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md)
                .padding(bottom = spacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                "Notion",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = spacing.sm),
            )

            if (conn.connected) {
                // ── Connected posture ────────────────────────────────
                Text(
                    text = if (conn.workspaceName.isBlank()) {
                        "Connected to your Notion workspace. Your pages appear in Browse."
                    } else {
                        "Connected to ${conn.workspaceName}. Your pages appear in Browse."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conn.oauthAvailable) {
                    BrassButton(
                        label = "Add more pages",
                        onClick = { launchConsent() },
                        variant = BrassButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Re-opens Notion's consent screen so you can grant access to more pages or databases.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BrassButton(
                    label = "Disconnect",
                    onClick = { viewModel.disconnectNotion() },
                    variant = BrassButtonVariant.Text,
                )
            } else {
                // ── Not-connected posture ────────────────────────────
                Text(
                    "Browse your own Notion pages and databases. Connect once and the pages you " +
                        "grant become listenable in Browse.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conn.oauthAvailable) {
                    BrassButton(
                        label = "Connect Notion",
                        onClick = { launchConsent() },
                        variant = BrassButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))
                    // Advanced toggle (a11y #481: Role.Button + label).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.Button,
                                onClickLabel = if (advancedExpanded) "Collapse advanced" else "Expand advanced",
                            ) { advancedExpanded = !advancedExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (advancedExpanded) "Advanced — paste a token" else "Advanced — paste a token instead",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (showTokenFields) {
                Text(
                    "Paste an Internal Integration Token from notion.so/my-integrations, and the " +
                        "database id you shared with it. The token is stored encrypted and never shown again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draftToken,
                    onValueChange = { draftToken = it },
                    label = { Text("Integration token") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draftDbId,
                    onValueChange = { draftDbId = it },
                    label = { Text("Database id (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    BrassButton(
                        label = "Save token",
                        onClick = {
                            viewModel.saveNotionToken(draftToken.trim(), draftDbId.trim())
                            draftToken = ""
                            draftDbId = ""
                            focusManager.clearFocus()
                            onDismiss()
                        },
                        variant = BrassButtonVariant.Primary,
                        enabled = draftToken.isNotBlank(),
                    )
                }
            }
        }
    }
}

/**
 * Launch [url] in a Chrome Custom Tab (keeps the user in-app for the OAuth
 * consent), falling back to a plain browser VIEW intent if no Custom Tabs
 * provider is installed. Mirrors AnthropicTeamsSignInScreen.openInCustomTab.
 */
private fun openInCustomTab(context: Context, url: String) {
    val uri = Uri.parse(url)
    runCatching {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
