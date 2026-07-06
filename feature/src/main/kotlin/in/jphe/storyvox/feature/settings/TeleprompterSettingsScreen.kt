package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1633 — dedicated teleprompter recording-preset subscreen, reached via
 * the gear in the Scripts top-bar (co-located with the teleprompter flow; NOT a
 * settings-hub row → off the hub-order chain). Built on [SettingsSubscreenScaffold]
 * so it shares the settings-family look + a11y vocabulary (Luna's IA note).
 *
 * These presets seed `RecordingViewModel`'s overlay flows on the next recording
 * session; the in-overlay controls write back to the same prefs (dual-write), so
 * this screen and the live overlay stay in sync. Sliders carry a
 * `stateDescription` so TalkBack reads the value, not a raw float (#160).
 */
@Composable
fun TeleprompterSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(
        title = stringResource(R.string.teleprompter_settings_title),
        onBack = onBack,
    ) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(
                modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md),
            )
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                val wpmTitle = stringResource(R.string.scripts_recording_wpm_title)
                val wpmValue = stringResource(R.string.scripts_recording_wpm_value, s.teleprompterWpm)
                SettingsSliderBlock(
                    title = wpmTitle,
                    valueLabel = wpmValue,
                    slider = {
                        Slider(
                            value = s.teleprompterWpm.toFloat(),
                            onValueChange = { viewModel.setTeleprompterWpm(it.toInt()) },
                            valueRange = 30f..500f,
                            modifier = Modifier.semantics {
                                contentDescription = wpmTitle
                                stateDescription = wpmValue
                            },
                        )
                    },
                )

                val cdTitle = stringResource(R.string.scripts_recording_countdown_title)
                val cdValue = if (s.teleprompterCountdownSec == 0) {
                    stringResource(R.string.scripts_recording_countdown_off)
                } else {
                    stringResource(R.string.scripts_recording_countdown_value, s.teleprompterCountdownSec)
                }
                SettingsSliderBlock(
                    title = cdTitle,
                    valueLabel = cdValue,
                    slider = {
                        Slider(
                            value = s.teleprompterCountdownSec.toFloat(),
                            onValueChange = { viewModel.setTeleprompterCountdownSec(it.toInt()) },
                            valueRange = 0f..10f,
                            modifier = Modifier.semantics {
                                contentDescription = cdTitle
                                stateDescription = cdValue
                            },
                        )
                    },
                )

                val opTitle = stringResource(R.string.scripts_recording_opacity_title)
                val opValue = stringResource(
                    R.string.scripts_recording_opacity_value,
                    (s.teleprompterOverlayOpacity * 100).toInt(),
                )
                SettingsSliderBlock(
                    title = opTitle,
                    valueLabel = opValue,
                    slider = {
                        Slider(
                            value = s.teleprompterOverlayOpacity,
                            onValueChange = { viewModel.setTeleprompterOverlayOpacity(it) },
                            valueRange = 0.3f..1.0f,
                            modifier = Modifier.semantics {
                                contentDescription = opTitle
                                stateDescription = opValue
                            },
                        )
                    },
                )

                val fsTitle = stringResource(R.string.scripts_recording_fontsize_title)
                val fsValue = stringResource(R.string.scripts_recording_fontsize_value, s.teleprompterFontSizeSp)
                SettingsSliderBlock(
                    title = fsTitle,
                    valueLabel = fsValue,
                    slider = {
                        Slider(
                            value = s.teleprompterFontSizeSp.toFloat(),
                            onValueChange = { viewModel.setTeleprompterFontSizeSp(it.toInt()) },
                            valueRange = 16f..64f,
                            modifier = Modifier.semantics {
                                contentDescription = fsTitle
                                stateDescription = fsValue
                            },
                        )
                    },
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.scripts_recording_mirror_title),
                    subtitle = stringResource(R.string.scripts_recording_mirror_subtitle),
                    checked = s.teleprompterMirror,
                    onCheckedChange = viewModel::setTeleprompterMirror,
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.scripts_recording_camera_title),
                    subtitle = stringResource(R.string.scripts_recording_camera_subtitle),
                    checked = s.teleprompterFrontCamera,
                    onCheckedChange = viewModel::setTeleprompterFrontCamera,
                )
            }
        }
    }
}
