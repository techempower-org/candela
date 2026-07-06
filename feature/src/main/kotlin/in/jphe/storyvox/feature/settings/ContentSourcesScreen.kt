package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
 * Scope (#1624 / #1644): renders the generic config seam (Reddit, Notion,
 * Slack, Matrix, Outline, Wikipedia, Prime Gaming) PLUS the bespoke rows the
 * static seam can't express — Discord (runtime guild-fetch dropdown), Telegram
 * (channel-discovery probe), the Google News full-article toggle, and the
 * Epub/Pdf local-folder SAF pickers. The bespoke rows are the SAME composables
 * the legacy monolith renders (widened `private` → `internal` in #1644),
 * threaded with their ViewModel state here — deliberately NOT seam
 * contributors: their imperative refresh/probe actions can't live behind the
 * declarative seam, and a Google-News bridge contributor would need
 * `SettingsRepositoryUi` injected into a contributor, forming a settings↔repo
 * Dagger cycle.
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
            // #1624 / #1644 — un-bury the bespoke source-config rows the legacy
            // "All settings" monolith owned so they're reachable from the
            // grouped hub. These call the SAME composables the monolith does
            // (widened `private` → `internal`), not a fork.
            val discordGuilds by viewModel.discordGuilds.collectAsStateWithLifecycle()
            val telegramBot by viewModel.telegramBotUsername.collectAsStateWithLifecycle()
            val telegramChannels by viewModel.telegramChannels.collectAsStateWithLifecycle()
            SettingsGroupCard {
                // Generic seam. An empty list renders nothing — defensive; the
                // live #1531 contributor set always registers the credentialed
                // sources, and the bespoke rows below keep the screen non-empty
                // regardless.
                SourceConfigSection(
                    sections = s.sourceConfigSections,
                    onValueChange = viewModel::setSourceConfigValue,
                )
                // Discord — bespoke: runtime guild-fetch dropdown + coalesce
                // slider the static seam can't express.
                DiscordConfigRow(
                    tokenConfigured = s.discordTokenConfigured,
                    serverId = s.discordServerId,
                    serverName = s.discordServerName,
                    coalesceMinutes = s.discordCoalesceMinutes,
                    guilds = discordGuilds,
                    onApiTokenChange = viewModel::setDiscordApiToken,
                    onServerSelected = viewModel::setDiscordServer,
                    onCoalesceMinutesChange = viewModel::setDiscordCoalesceMinutes,
                    onRefreshGuilds = viewModel::refreshDiscordGuilds,
                )
                // Telegram — bespoke: getMe/getUpdates channel-discovery probe.
                TelegramConfigRow(
                    tokenConfigured = s.telegramTokenConfigured,
                    botUsername = telegramBot,
                    channels = telegramChannels,
                    onApiTokenChange = viewModel::setTelegramApiToken,
                    onRefreshProbe = viewModel::refreshTelegramProbe,
                )
                // #1295 — Google News full-article text (opt-in, default OFF).
                // Rendered inline (not a seam contributor): a bridge would need
                // SettingsRepositoryUi injected into a contributor → Dagger cycle.
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_google_news_full_text_title),
                    subtitle = stringResource(R.string.settings_google_news_full_text_subtitle),
                    checked = s.googleNewsFullArticleText,
                    onCheckedChange = viewModel::setGoogleNewsFullArticleText,
                )
            }
            // Local folder reader-sources — SAF OpenDocumentTree pickers for
            // reading .epub / .pdf files off device storage. Bespoke: no seam
            // field type expresses a native folder picker + persistable URI
            // grant. (#1644 assessment: these carry reader-source folder config
            // — they are NOT the export writers — so they belong here.)
            SettingsSectionHeader(label = "Local folders")
            SettingsGroupCard {
                EpubFolderPickerRow(viewModel = viewModel)
                PdfFolderPickerRow(viewModel = viewModel)
            }
        }
    }
}
