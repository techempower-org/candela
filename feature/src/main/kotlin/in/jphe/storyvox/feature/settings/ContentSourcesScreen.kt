package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1630 — Settings → Content Sources subscreen (Wave 1 of the #1624
 * settings overhaul; lives in the "Content & Sources" hub group).
 *
 * Per-source configuration — API keys, tokens, feed-URL overrides, behaviour
 * toggles — used to be reachable ONLY by scrolling the 4,100-line legacy
 * "All settings" monolith, so it was invisible to anyone navigating the
 * grouped hub. This surfaces the SAME generic config-field seam (#1531:
 * `SourceConfigContributor` → [UiSourceConfigSection], streamed on
 * `UiSettings.sourceConfigSections`) behind a dedicated, discoverable route.
 *
 * It reuses the legacy screen's [SourceConfigSection] field editor verbatim
 * (widened `private` → `internal` in #1630), so there is exactly ONE
 * field-editor implementation, not a fork. The seam auto-grows: a new
 * credentialed source that contributes a `SourceConfigContributor` appears
 * here with zero edits to this file.
 *
 * Scope note (#1630 slice 1): renders every source already on the generic
 * seam (Reddit, Notion, Prime Gaming, Slack, Matrix). Migrating the remaining
 * bespoke legacy rows (Telegram, Outline, Google News, Wikipedia) onto the
 * seam, and the bespoke tail (Discord server-select, Epub/Pdf folder pickers),
 * are follow-up slices — each an independently shippable contributor.
 */
@Composable
fun ContentSourcesSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(
        title = stringResource(R.string.settings_hub_content_sources_title),
        onBack = onBack,
    ) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            if (s.sourceConfigSections.isEmpty()) {
                // Defensive: the live contributor set (#1531) always registers
                // the credentialed sources, so this rarely shows — but if a
                // build ships with none, a bare screen would read as broken.
                Text(
                    text = stringResource(R.string.settings_content_sources_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SettingsGroupCard {
                    SourceConfigSection(
                        sections = s.sourceConfigSections,
                        onValueChange = viewModel::setSourceConfigValue,
                    )
                }
            }
        }
    }
}
