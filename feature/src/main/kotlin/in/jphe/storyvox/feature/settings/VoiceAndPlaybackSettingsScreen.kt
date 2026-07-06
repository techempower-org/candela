package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Column
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
 * Settings → Voice & Playback subscreen (follow-up to #440 / #467).
 *
 * Houses the auditory knobs a listener touches *for this story, this
 * session* — voice, speed, pitch, cadence, sentence-by-sentence
 * pacing. The legacy long-scroll [SettingsScreen] still renders the
 * same rows (so power users searching from the "All settings" escape
 * hatch keep their muscle memory); this subscreen is the curated
 * single-purpose surface reached from the [SettingsHubScreen] gear-
 * icon hub.
 *
 * Row order — most-touched first:
 *  1. Voice library link
 *  2. Speed slider (with the 1× tick anchor — #273)
 *  3. Pitch slider (with the 1× tick anchor)
 *  4. Punctuation cadence slider (#109)
 *  5. High-quality pitch interpolation switch (#193)
 *  6. Auto language detection switch (#1233)
 *  7. Pronunciation dictionary link (#135)
 *
 * Then two labelled groups (#1577):
 *  - **Playback controls** — skip distance (#593), rewind-to-start (#594).
 *  - **Sleep timer** — shake-to-extend enable (#150, moved here from Reading)
 *    + duration (#595), Bedtime auto-arm, and Do Not Disturb auto-sleep
 *    (#1190/#1574). Consolidated so the whole sleep-timer feature lives on one
 *    screen instead of straddling Reading and Voice & Playback.
 */
@Composable
fun VoiceAndPlaybackSettingsScreen(
    onBack: () -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    onOpenPronunciationDict: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val context = androidx.compose.ui.platform.LocalContext.current

    SettingsSubscreenScaffold(title = stringResource(R.string.settings_voice_title), onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_voice_library_title),
                    subtitle = stringResource(R.string.settings_voice_library_subtitle),
                    onClick = onOpenVoiceLibrary,
                )

                // Speed and Pitch sliders preserve the v0.4.x tick-label
                // pattern: a single "▲ 1×" mark anchors the natural value,
                // tap snaps the slider back to neutral. See the matching
                // block in [SettingsScreen] for the rationale (#273).
                val speedMin = 0.5f
                val speedMax = 4.0f
                val naturalValue = 1.0f
                val speedNaturalFraction = (naturalValue - speedMin) / (speedMax - speedMin)
                val speedCd = stringResource(R.string.settings_voice_speed_cd)
                val speedState = stringResource(R.string.settings_voice_speed_state, "%.2f".format(s.effectiveSpeed))
                val naturalTickLabel = stringResource(R.string.settings_voice_tick_natural)
                SettingsSliderBlock(
                    title = stringResource(R.string.settings_voice_speed_title),
                    valueLabel = stringResource(R.string.settings_voice_speed_value, "%.2f".format(s.effectiveSpeed)),
                    slider = {
                        Column {
                            Slider(
                                value = s.effectiveSpeed,
                                onValueChange = viewModel::setSpeed,
                                valueRange = speedMin..speedMax,
                                modifier = Modifier.semantics {
                                    contentDescription = speedCd
                                    stateDescription = speedState
                                },
                            )
                            SliderTickLabels(
                                ticks = listOf(naturalTickLabel to speedNaturalFraction),
                                onTickTap = { viewModel.setSpeed(naturalValue) },
                            )
                        }
                    },
                )

                val pitchMin = 0.6f
                val pitchMax = 1.4f
                val pitchNaturalFraction = (naturalValue - pitchMin) / (pitchMax - pitchMin)
                val pitchCd = stringResource(R.string.settings_voice_pitch_cd)
                val pitchState = stringResource(R.string.settings_voice_pitch_state, "%.2f".format(s.effectivePitch))
                SettingsSliderBlock(
                    title = stringResource(R.string.settings_voice_pitch_title),
                    valueLabel = stringResource(R.string.settings_voice_pitch_value, "%.2f".format(s.effectivePitch)),
                    slider = {
                        Column {
                            Slider(
                                value = s.effectivePitch,
                                onValueChange = viewModel::setPitch,
                                valueRange = pitchMin..pitchMax,
                                modifier = Modifier.semantics {
                                    contentDescription = pitchCd
                                    stateDescription = pitchState
                                },
                            )
                            SliderTickLabels(
                                ticks = listOf(naturalTickLabel to pitchNaturalFraction),
                                onTickTap = { viewModel.setPitch(naturalValue) },
                            )
                        }
                    },
                )

                PunctuationPauseSlider(
                    multiplier = s.punctuationPauseMultiplier,
                    onMultiplierChange = viewModel::setPunctuationPauseMultiplier,
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_voice_hq_pitch_title),
                    subtitle = if (s.pitchInterpolationHighQuality) {
                        stringResource(R.string.settings_voice_hq_pitch_on)
                    } else {
                        stringResource(R.string.settings_voice_hq_pitch_off)
                    },
                    checked = s.pitchInterpolationHighQuality,
                    onCheckedChange = viewModel::setPitchInterpolationHighQuality,
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_voice_autolang_title),
                    subtitle = if (s.autoLanguageDetectionEnabled) {
                        stringResource(R.string.settings_voice_autolang_on)
                    } else {
                        stringResource(R.string.settings_voice_autolang_off)
                    },
                    checked = s.autoLanguageDetectionEnabled,
                    onCheckedChange = viewModel::setAutoLanguageDetection,
                )

                SettingsLinkRow(
                    title = stringResource(R.string.settings_voice_pronunciation_title),
                    subtitle = stringResource(R.string.settings_voice_pronunciation_subtitle),
                    onClick = onOpenPronunciationDict,
                )
            }

            // Issues #593 / #594 — playback transport tunables. The
            // skip distance chip (#593) drives the +N s / -N s
            // transport buttons; the rewind-to-start chip (#594)
            // controls how SkipPrevious behaves mid-chapter. Bundled
            // because the two prefs pair conceptually — users
            // calibrate them together for their content style.
            SettingsSectionHeader(label = stringResource(R.string.settings_voice_transport_group_title))
            SettingsGroupCard {
                // #593 — skip distance. Matches Spotify / Apple Music
                // / Pocket Casts default of 30s; users on dense
                // chapters often want 10/15, podcast users 45/60.
                val skipOptions = listOf(10, 15, 30, 45, 60)
                val skipSelectedIndex = skipOptions
                    .indexOfFirst { it == s.skipDistanceSec }
                    .let { if (it < 0) skipOptions.indexOf(30) else it }
                SettingsSegmentedBlock(
                    title = stringResource(R.string.settings_voice_skip_distance_title),
                    subtitle = stringResource(R.string.settings_voice_skip_distance_subtitle),
                    options = skipOptions.map { stringResource(R.string.settings_voice_skip_distance_option, it) },
                    selectedIndex = skipSelectedIndex,
                    onSelected = { idx -> viewModel.setSkipDistanceSec(skipOptions[idx]) },
                )

                // #594 — rewind-to-start window. When you tap
                // SkipPrevious *past* this many seconds into a
                // chapter, it rewinds to the chapter start. Within
                // this window, it jumps to the previous chapter.
                // 0 = always go to previous chapter (radio / podcast
                // users on short content who want fast prev-track
                // navigation).
                val rewindOptions = listOf(0, 1, 3, 5, 10)
                val rewindSelectedIndex = rewindOptions
                    .indexOfFirst { it == s.rewindToStartThresholdSec }
                    .let { if (it < 0) rewindOptions.indexOf(3) else it }
                val rewindOffLabel = stringResource(R.string.settings_voice_rewind_off)
                SettingsSegmentedBlock(
                    title = stringResource(R.string.settings_voice_rewind_title),
                    subtitle = stringResource(R.string.settings_voice_rewind_subtitle),
                    options = rewindOptions.map { if (it == 0) rewindOffLabel else stringResource(R.string.settings_voice_rewind_option, it) },
                    selectedIndex = rewindSelectedIndex,
                    onSelected = { idx -> viewModel.setRewindToStartThresholdSec(rewindOptions[idx]) },
                )
            }

            // Issue #1577 — sleep-timer settings, consolidated. Before this,
            // the shake-to-extend ENABLE toggle lived under Settings → Reading
            // while its DURATION, the Bedtime auto-arm, and the DND auto-sleep
            // toggle (#1190/#1574) lived here — so no single screen held the
            // whole feature and the flagship DND toggle was doubly buried. All
            // four now sit together under one labelled "Sleep timer" group,
            // enable → duration → auto-arm → Do Not Disturb.
            SettingsSectionHeader(label = stringResource(R.string.settings_voice_sleep_group_title))
            SettingsGroupCard {
                // Issue #1590 — default sleep-timer duration. This is what the
                // quick sleep toggle arms (pre-#1590 hardcoded 15 in
                // core-playback's PlaybackController); the presets mirror the
                // player-sheet options (5 / 15 / 30 / 60).
                val sleepDefaultOptions = listOf(5, 15, 30, 60)
                val sleepDefaultIndex = sleepDefaultOptions
                    .indexOfFirst { it == s.sleepTimerDefaultMinutes }
                    .let { if (it < 0) sleepDefaultOptions.indexOf(15) else it }
                SettingsSegmentedBlock(
                    title = stringResource(R.string.settings_voice_sleep_default_title),
                    subtitle = stringResource(R.string.settings_voice_sleep_default_subtitle),
                    options = sleepDefaultOptions.map {
                        stringResource(R.string.settings_voice_sleep_default_option, it)
                    },
                    selectedIndex = sleepDefaultIndex,
                    onSelected = { idx ->
                        viewModel.setSleepTimerDefaultMinutes(sleepDefaultOptions[idx])
                    },
                )

                // #150 — shake-to-extend enable (moved here from Reading in
                // #1577 so it sits directly above the duration it governs).
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_voice_shake_enable_title),
                    subtitle = stringResource(R.string.settings_voice_shake_enable_subtitle),
                    checked = s.sleepShakeToExtendEnabled,
                    onCheckedChange = viewModel::setSleepShakeToExtendEnabled,
                )

                // Issue #595 — sleep-timer shake-to-extend duration.
                // When the timer's 10-second fade tail starts and the
                // user shakes the device, the timer extends by this
                // many minutes. Pre-fix this was hardcoded at 15
                // (StoryvoxPlaybackService.LEGACY_SHAKE_EXTEND_MINUTES);
                // listeners who fall asleep quickly often want 5, and
                // those on a slower wind-down often want 30.
                val shakeOptions = listOf(5, 10, 15, 30)
                val shakeIndex = shakeOptions
                    .indexOfFirst { it == s.sleepShakeExtendMinutes }
                    .let { if (it < 0) shakeOptions.indexOf(15) else it }
                SettingsSegmentedBlock(
                    title = stringResource(R.string.settings_voice_shake_extend_title),
                    subtitle = stringResource(R.string.settings_voice_shake_extend_subtitle),
                    options = shakeOptions.map { stringResource(R.string.settings_voice_shake_extend_option, it) },
                    selectedIndex = shakeIndex,
                    onSelected = { idx ->
                        viewModel.setSleepShakeExtendMinutes(shakeOptions[idx])
                    },
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_voice_bedtime_auto_title),
                    subtitle = stringResource(R.string.settings_voice_bedtime_auto_subtitle),
                    checked = s.sleepBedtimeAutoEnabled,
                    onCheckedChange = viewModel::setSleepBedtimeAutoEnabled,
                )

                // Issue #1190 — auto Do Not Disturb with the sleep timer.
                // DND access is a *special* grant the user flips manually
                // (the manifest ACCESS_NOTIFICATION_POLICY permission is
                // not enough on its own), so flipping this ON hops once to
                // the system DND-access screen when access isn't held yet.
                // The actual filter swap happens in core-playback's
                // AndroidDndController, which no-ops until access is
                // granted — the toggle never dead-ends.
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_voice_dnd_sleep_title),
                    subtitle = stringResource(R.string.settings_voice_dnd_sleep_subtitle),
                    checked = s.dndWithSleepTimerEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setDndWithSleepTimerEnabled(enabled)
                        if (enabled) {
                            val nm = context.getSystemService(android.app.NotificationManager::class.java)
                            if (nm != null && !nm.isNotificationPolicyAccessGranted) {
                                // Guarded — a stripped ROM may not resolve
                                // the DND-access settings activity (mirrors
                                // the uriHandler guard in #1177).
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
