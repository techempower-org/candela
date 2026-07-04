package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/** Hosted privacy policy (source: `docs/privacy.md`). Play Console requires a
 *  reachable privacy-policy URL, and the policy must be reachable from inside
 *  the app too — surfaced here per #1138. */
private const val PRIVACY_POLICY_URL = "https://candela.techempower.org/privacy/"
private const val ACCESSIBILITY_STATEMENT_URL = "https://candela.techempower.org/accessibility/"

/** Content-report mailto (#1140). Play Store IARC content rating expects a
 *  reporting channel for the user-generated content Candela surfaces from
 *  third-party sources (AO3, Royal Road, etc.). Subject + body are URL-encoded
 *  at click time via [android.net.Uri.encode] so the em-dash and newlines
 *  survive the mailto: scheme. */
private const val CONTENT_REPORT_EMAIL = "support@techempower.org"
private const val CONTENT_REPORT_SUBJECT = "Candela — Content Report"
private const val CONTENT_REPORT_BODY = "Source: \nTitle: \nURL: \nDescription of concern: \n"

private fun contentReportMailto(): String =
    "mailto:$CONTENT_REPORT_EMAIL" +
        "?subject=${android.net.Uri.encode(CONTENT_REPORT_SUBJECT)}" +
        "&body=${android.net.Uri.encode(CONTENT_REPORT_BODY)}"

/**
 * Settings → About subscreen (follow-up to #440 / #467).
 *
 * Build identity card for bug reports: version + sigil name (the
 * deterministic adjective+noun realm-sigil derived from the build's
 * git hash), branch, dirty flag, build date, and the v0.5.00
 * graduation milestone pill when the build qualifies.
 *
 * The legacy long-scroll [SettingsScreen] renders the same content
 * inside its About section card; this subscreen surfaces it behind
 * a dedicated route for users who reach Settings → About via the
 * hub.
 */
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    /** Issue #1142 — open the Open-source licenses subscreen. The About hub
     *  advertised "open-source notices" with nothing behind the promise;
     *  this routes to the AboutLibraries-backed list. Default no-op keeps
     *  previews / tests simple; the NavHost passes a real navigate. */
    onOpenLicenses: () -> Unit = {},
    /** Issue #1463 — open the "About impact sharing" explainer subscreen. Default
     *  no-op keeps previews / tests simple; the NavHost passes a real navigate. */
    onOpenImpactSharing: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val uriHandler = LocalUriHandler.current

    SettingsSubscreenScaffold(title = stringResource(R.string.settings_about_title), onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                Column(
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    Text(
                        text = stringResource(R.string.settings_about_version, s.sigil.versionName),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // Hidden candle egg (Candela v1.1) — 7 taps on the
                    // brass sigil name lights the flame + whispers the
                    // meaning of "Candela".
                    `in`.jphe.storyvox.ui.component.CandleTapEgg {
                        Text(
                            text = s.sigil.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val dirtySuffix = if (s.sigil.dirty) stringResource(R.string.settings_about_dirty) else ""
                    val builtSuffix = stringResource(R.string.settings_about_built, s.sigil.built.take(10))
                    Text(
                        text = "${s.sigil.branch}$dirtySuffix$builtSuffix",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isV0500MilestoneBuild(s.sigil.versionName)) {
                        MilestoneBadgePill(s.sigil.versionName)
                    }
                }
            }

            SettingsGroupCard {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_about_privacy_policy),
                    subtitle = stringResource(R.string.settings_about_privacy_policy_subtitle),
                    onClick = { runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) } },
                )
            }
            SettingsGroupCard {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_about_accessibility),
                    subtitle = stringResource(R.string.settings_about_accessibility_subtitle),
                    onClick = { runCatching { uriHandler.openUri(ACCESSIBILITY_STATEMENT_URL) } },
                )
            }
            // Issue #1463 — findability for the opt-in anonymous impact-sharing
            // feature. Explainer only (no toggle): sharing is a user-triggered
            // action on the Listening Stats screen.
            SettingsGroupCard {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_about_impact_title),
                    subtitle = stringResource(R.string.settings_about_impact_subtitle),
                    onClick = onOpenImpactSharing,
                )
            }
            SettingsGroupCard {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_about_report_content),
                    subtitle = stringResource(R.string.settings_about_report_content_subtitle),
                    onClick = { runCatching { uriHandler.openUri(contentReportMailto()) } },
                )
            }
            SettingsGroupCard {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_about_licenses),
                    subtitle = stringResource(R.string.settings_about_licenses_subtitle),
                    onClick = onOpenLicenses,
                )
            }
        }
    }
}
