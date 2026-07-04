package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.stats.ImpactSharingDisclosure
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/** Hosted privacy policy, impact-sharing anchor (docs/privacy.md §2.9). */
private const val IMPACT_PRIVACY_URL = "https://candela.techempower.org/privacy/"

/**
 * Issue #1463 — Settings → About → **Impact sharing** explainer subscreen.
 *
 * Findability only (§0.5): there is NO toggle here — sharing is a user-triggered
 * action taken from the Listening Stats screen, and the act of sharing is the consent.
 * This screen exists so a user who wants to understand (or find) the feature from
 * Settings can read exactly what it does, what's shared, and what's never shared —
 * reusing the same disclosure the preview sheet shows so the promise is worded once.
 */
@Composable
fun ImpactSharingAboutScreen(
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val uriHandler = LocalUriHandler.current

    SettingsSubscreenScaffold(title = stringResource(R.string.impact_about_title), onBack = onBack) { padding ->
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                Column(
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.impact_about_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.impact_about_where),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            SettingsGroupCard {
                Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.md)) {
                    ImpactSharingDisclosure()
                    Text(
                        text = stringResource(R.string.impact_preview_withdrawal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }
            }

            SettingsGroupCard {
                TextButton(
                    onClick = { runCatching { uriHandler.openUri(IMPACT_PRIVACY_URL) } },
                    modifier = Modifier.padding(horizontal = spacing.sm),
                ) {
                    Text(stringResource(R.string.impact_preview_privacy_link))
                }
            }
        }
    }
}
