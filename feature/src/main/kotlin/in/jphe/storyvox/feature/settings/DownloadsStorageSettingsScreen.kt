package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.settings.components.SectionHeading
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1632 — Settings → Downloads & Storage subscreen (hub group 4, per the
 * #1624 IA — between Content & Sources and AI).
 *
 * Consolidates download/storage prefs that were scattered — Wi-Fi-only +
 * update-check interval lived only in the legacy [SettingsScreen] monolith,
 * and cache quota + clear-cache lived under Performance — and adds the new
 * global **default download mode** applied to newly-added fictions (the
 * per-`Fiction` mode is untouched; see `RealFictionRepositoryUi.follow`).
 *
 * Curated view over shared prefs: it reuses [CacheSizeSelector] / [CacheUsageRow]
 * and the shared switch/slider rows rather than reimplementing them, and the
 * legacy "All settings" page keeps the same controls as the power-user escape
 * hatch. Strings are EN-only (#1583). Accessibility contract preserved: labelled
 * [SectionHeading]s, a `Role.RadioButton` selectable group for the mode picker,
 * and a `stateDescription` on the interval slider.
 */
@Composable
fun DownloadsStorageSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(
        title = stringResource(R.string.settings_downloads_title),
        onBack = onBack,
    ) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(
                modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md),
            )
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            // ── Default download mode (new global pref) ─────────────────
            SectionHeading(label = stringResource(R.string.settings_downloads_mode_heading))
            SettingsGroupCard {
                DownloadModeSelector(
                    selected = s.defaultDownloadMode,
                    onSelect = viewModel::setDefaultDownloadMode,
                )
            }

            // ── Network ─────────────────────────────────────────────────
            SectionHeading(label = stringResource(R.string.settings_downloads_network_heading))
            SettingsGroupCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_downloads_wifi_title),
                    subtitle = stringResource(R.string.settings_downloads_wifi_subtitle),
                    checked = s.downloadOnWifiOnly,
                    onCheckedChange = viewModel::setWifiOnly,
                )
                // Hoisted out of the semantics lambda (stringResource is
                // @Composable and can't be called inside it).
                val pollTitle = stringResource(R.string.settings_downloads_poll_title)
                val pollValue = stringResource(R.string.settings_downloads_poll_value, s.pollIntervalHours)
                SettingsSliderBlock(
                    title = pollTitle,
                    valueLabel = pollValue,
                    subtitle = stringResource(R.string.settings_downloads_poll_subtitle),
                    slider = {
                        Slider(
                            value = s.pollIntervalHours.toFloat(),
                            onValueChange = { viewModel.setPollHours(it.toInt().coerceIn(1, 24)) },
                            valueRange = 1f..24f,
                            modifier = Modifier.semantics {
                                contentDescription = pollTitle
                                stateDescription = pollValue
                            },
                        )
                    },
                )
            }

            // ── Storage (reused from Performance) ───────────────────────
            SectionHeading(label = stringResource(R.string.settings_downloads_storage_heading))
            SettingsGroupCard {
                CacheSizeSelector(
                    quotaBytes = s.cacheQuotaBytes,
                    onQuotaChange = viewModel::setCacheQuotaBytes,
                )
                CacheUsageRow(
                    usedBytes = s.cacheUsedBytes,
                    quotaBytes = s.cacheQuotaBytes,
                    onClearCache = viewModel::clearCache,
                )
            }
        }
    }
}

/**
 * Radio-group picker for the global default [DownloadMode]. Standard Material3
 * a11y shape: a [selectableGroup] of rows, each [selectable] with
 * [Role.RadioButton], the [RadioButton] itself `onClick = null` so the whole
 * row is the single TalkBack node announcing its selected state.
 */
@Composable
private fun DownloadModeSelector(
    selected: DownloadMode,
    onSelect: (DownloadMode) -> Unit,
) {
    val spacing = LocalSpacing.current
    val options = listOf(
        Triple(
            DownloadMode.Lazy,
            R.string.settings_downloads_mode_lazy,
            R.string.settings_downloads_mode_lazy_desc,
        ),
        Triple(
            DownloadMode.Eager,
            R.string.settings_downloads_mode_eager,
            R.string.settings_downloads_mode_eager_desc,
        ),
        Triple(
            DownloadMode.Subscribe,
            R.string.settings_downloads_mode_subscribe,
            R.string.settings_downloads_mode_subscribe_desc,
        ),
    )
    Column(modifier = Modifier.selectableGroup()) {
        options.forEach { (mode, labelRes, descRes) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = mode == selected,
                        onClick = { onSelect(mode) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = spacing.sm, horizontal = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == selected, onClick = null)
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
