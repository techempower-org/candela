package `in`.jphe.storyvox.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.settings.components.SectionHeading
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1631 — Settings → Notifications subscreen (Wave 1 of the #1624
 * settings overhaul; lives in the "Notifications" hub group).
 *
 * Notification prefs used to be reachable ONLY by scrolling the 4,100-line
 * legacy "All settings" monolith. This un-buries them behind a dedicated,
 * discoverable route (mirrors [ContentSourcesSettingsScreen]):
 *
 *  - **System permission status + deep-link** — every alert below is silenced
 *    by the OS if the app's notification permission is off
 *    (`NewChapterNotifier` guards on `areNotificationsEnabled()`), so surface
 *    it first with a one-tap deep-link to the system settings that fix it.
 *  - **Per-source new-chapter alerts** — Royal Road / KVMR / Wikipedia (#383).
 *    Reuses the existing `setInboxNotify*` setters, so this and the legacy page
 *    write the same prefs (single source of truth); each toggle suppresses both
 *    the Android notification and the in-app Inbox event for that source.
 *  - **Deadline reminders** — a master `deadlineRemindersEnabled` pref (#1515).
 *    Default ON; toggling it OFF gates only *new* deadline scheduling in the
 *    Deadline keeper. Reminders already armed — and the boot re-arm — are left
 *    alone, so a benefits deadline the user already set is never silently dropped.
 *
 * Scope note: slice 1 (#1647) landed the `inboxNotify*` un-bury + the permission
 * affordance; slice 2 (this change) adds the `deadlineRemindersEnabled` gate — a
 * new pref across UiContracts / repo / VM plus a VM-side scheduling gate in
 * [DeadlineKeeperViewModel][in.jphe.storyvox.feature.techempower.deadline.DeadlineKeeperViewModel],
 * per the spec in `scratch/candela-1631-notifications/`.
 */
@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val context = LocalContext.current

    SettingsSubscreenScaffold(
        title = stringResource(R.string.settings_hub_notifications_title),
        onBack = onBack,
    ) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        // Read once on entry; a full live-updating status would need an
        // on-resume refresh, deferred — the deep-link is the actionable part.
        val notificationsEnabled = remember {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        SettingsSubscreenBody(padding) {
            // System permission first — everything else no-ops without it.
            SettingsGroupCard {
                SettingsRow(
                    title = stringResource(R.string.settings_notifications_permission_title),
                    subtitle = stringResource(
                        if (notificationsEnabled) {
                            R.string.settings_notifications_permission_on
                        } else {
                            R.string.settings_notifications_permission_off
                        },
                    ),
                    onClick = {
                        // ACTION_APP_NOTIFICATION_SETTINGS is O+; runCatching
                        // guards the rare OEM that doesn't resolve it.
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    },
                )
            }

            SectionHeading(label = stringResource(R.string.settings_notifications_new_chapter_heading))
            SettingsGroupCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_inbox_royalroad_title),
                    subtitle = stringResource(R.string.settings_inbox_royalroad_subtitle),
                    checked = s.inboxNotifyRoyalRoad,
                    onCheckedChange = viewModel::setInboxNotifyRoyalRoad,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_inbox_kvmr_title),
                    subtitle = stringResource(R.string.settings_inbox_kvmr_subtitle),
                    checked = s.inboxNotifyKvmr,
                    onCheckedChange = viewModel::setInboxNotifyKvmr,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_inbox_wikipedia_title),
                    subtitle = stringResource(R.string.settings_inbox_wikipedia_subtitle),
                    checked = s.inboxNotifyWikipedia,
                    onCheckedChange = viewModel::setInboxNotifyWikipedia,
                )
            }

            SectionHeading(label = stringResource(R.string.settings_notifications_deadline_heading))
            SettingsGroupCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_deadline_reminders_title),
                    subtitle = stringResource(R.string.settings_deadline_reminders_subtitle),
                    checked = s.deadlineRemindersEnabled,
                    onCheckedChange = viewModel::setDeadlineRemindersEnabled,
                )
            }
        }
    }
}
