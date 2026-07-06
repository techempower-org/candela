package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Toc
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.settings.components.SectionHeading
import `in`.jphe.storyvox.ui.component.MagicTitleBar
import `in`.jphe.storyvox.ui.component.TestTags
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #773 — Settings hub search filter. Threaded to every
 * [SettingsHubRow] via a CompositionLocal so the call sites in
 * [SettingsHubScreen] stay declarative: the rows skip themselves
 * when the current query doesn't match title or subtitle. Empty
 * query (the default) matches everything.
 */
private val LocalSettingsHubQuery = staticCompositionLocalOf { "" }

/**
 * Issue #1577 — the hub search used to match a row's title + subtitle only,
 * so a setting that lived *inside* a subscreen was invisible to search: typing
 * "dnd", "bedtime", "handbook", "privacy", or "impact" surfaced nothing even
 * though every one of those settings ships today (the buried DND auto-sleep
 * toggle of #1574 is the worked example). Each row now also carries a
 * [SettingsHubSection.keywords] list — the non-obvious synonyms and the names
 * of the individual knobs it houses — and the query matches against those too.
 * Keywords are internal search tokens, never rendered, so they don't need
 * translation and stay in English to match the (English) settings surface.
 */
internal fun matchesHubQuery(
    query: String,
    title: String,
    subtitle: String,
    keywords: List<String> = emptyList(),
): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return title.contains(q, ignoreCase = true) ||
        subtitle.contains(q, ignoreCase = true) ||
        keywords.any { it.contains(q, ignoreCase = true) }
}

/**
 * Issue #440 — Settings hub screen. Follow-up to #467 wired every
 * named section to a dedicated subscreen.
 *
 * v0.5.36 wired the gear icon directly to [SettingsScreen], a 3,600-line
 * flat-scroll page that opened on the Voice & Playback section with no
 * top-of-page map. New users had no way to discover what Settings
 * contained without scrolling past every card; the top bar still read
 * "Voice & Playback" while the user scrolled through Reading, Performance,
 * AI, etc., so the title disagreed with what was actually on screen.
 *
 * The hub is the gear-icon destination: a search box + a set of link
 * rows, each carrying a one-line subtitle that previews its contents and
 * routes to a dedicated subscreen.
 *
 * ## Grouping (#1624)
 *
 * v1.12 the hub had grown to a flat 20-row list — every new feature
 * appended a row, so scanning it meant reading twenty undifferentiated
 * lines. The rows are now organised into labelled categories (a
 * [SectionHeading] above its own [SettingsGroupCard] via [HubGroup]), so
 * the eye can jump to a category instead of scanning the whole list. The
 * end-state IA (JP-approved mockup, epic #1624) is eight groups; the two
 * that don't fit yet — Notifications and Downloads & Storage — hold
 * settings still buried in the legacy monolith and materialise when their
 * gap PRs (#1631 / #1632) add subscreens. This reorg lands the seven
 * groups whose rows exist today, in most-touched-first order:
 *
 * 1. **Voice & Audio** — Voice & Playback · Voice library · Cloud Voices · Pronunciation.
 * 2. **Reading & Display** — Reading · Appearance · Accessibility.
 * 3. **Content & Sources** — Plugins · Account · Memory Palace · Bookshare.
 * 4. **AI** — AI · AI sessions.
 * 5. **Tools & Features** — Listening stats · Morning Briefing · Scripts.
 * 6. **System** — Performance · Advanced · Developer.
 * 7. **About & Help** — About.
 *
 * The long [SettingsScreen] is preserved as an "All settings" escape hatch
 * for power users who want everything on one searchable page; a dedicated
 * row below a divider at the very bottom routes there explicitly so the
 * affordance isn't lost.
 *
 * Search (#773 / #1577) is unchanged: each [SettingsHubRow] self-hides on a
 * non-matching query, and each [HubGroup] additionally hides its heading +
 * card when *none* of its rows match, so a filtered search never strands an
 * orphan heading over an empty card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    onNavigateBack: () -> Unit,
    onOpenAllSettings: () -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    onOpenPluginManager: () -> Unit,
    onOpenAiSessions: () -> Unit,
    onOpenPronunciationDict: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenVoicePlayback: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenPerformance: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenMemoryPalace: () -> Unit,
    onOpenBookshare: () -> Unit,
    onOpenAbout: () -> Unit,
    /**
     * v1 settings-bundle-7 — Advanced subscreen. Default no-op so
     * callers (and the smoke test) that haven't wired it yet still
     * compile; production wiring lives in
     * [`in.jphe.storyvox.navigation.StoryvoxNavHost`].
     */
    onOpenAdvanced: () -> Unit = {},
    /**
     * Issue #1235 — Listening stats dashboard. Default no-op so existing
     * callers / smoke tests compile without wiring it; production wiring
     * lives in [`in.jphe.storyvox.navigation.StoryvoxNavHost`].
     */
    onOpenStats: () -> Unit = {},
    /**
     * Issue #1467 — morning-briefing / personal-podcast queue. Default no-op so
     * existing callers / smoke tests compile without wiring it; production
     * wiring lives in [`in.jphe.storyvox.navigation.StoryvoxNavHost`].
     */
    onOpenBriefing: () -> Unit = {},
    /**
     * Issue #1369 — teleprompter script manager. Default no-op so existing
     * callers / smoke tests compile without wiring it; production wiring lives
     * in [`in.jphe.storyvox.navigation.StoryvoxNavHost`].
     */
    onOpenScripts: () -> Unit = {},
    /** Issue #1634 — Benefits suite (Screener/Decoder) re-discovery entry.
     *  Default no-op so callers / the smoke test compile without wiring it. */
    onOpenBenefits: () -> Unit = {},
    /**
     * Issue #1624 — Cloud Voices (Azure HD / Dragon HD). The subscreen +
     * [`SETTINGS_CLOUD_VOICES`] route already shipped, but there was no hub
     * row — the only path to an Azure key was Plugins → Azure family →
     * Configure. Default no-op so existing callers / smoke tests compile;
     * production wiring lives in [`in.jphe.storyvox.navigation.StoryvoxNavHost`].
     */
    onOpenCloudVoices: () -> Unit = {},
    /**
     * Issue #1630 — Content Sources subscreen (per-source keys / tokens / URLs,
     * un-buried from the legacy monolith). Default no-op so existing callers /
     * smoke tests compile; production wiring lives in
     * [`in.jphe.storyvox.navigation.StoryvoxNavHost`].
     */
    onOpenContentSources: () -> Unit = {},
    /** Issue #1632 — Downloads & Storage subscreen. Default no-op so callers
     *  / smoke tests compile without wiring it; production wiring in
     *  [`in`.jphe.storyvox.navigation.StoryvoxNavHost]. */
    onOpenDownloads: () -> Unit = {},
    /**
     * Issue #1631 — Notifications subscreen (new-chapter alerts + system
     * permission). Default no-op so callers / smoke tests compile; production
     * wiring lives in [`in.jphe.storyvox.navigation.StoryvoxNavHost`].
     */
    onOpenNotifications: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var query by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            // #830 — shared title bar across all primary-nav surfaces.
            MagicTitleBar(
                title = stringResource(R.string.settings_hub_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // UI-test selector for the settings hub section index.
                .testTag(TestTags.SettingsList)
                .padding(scaffoldPadding)
                .verticalScroll(rememberScrollState())
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            SectionHeading(
                label = stringResource(R.string.settings_hub_heading),
                icon = Icons.Outlined.AutoAwesome,
                descriptor = stringResource(R.string.settings_hub_descriptor),
            )
            // Issue #773 — search filter. Case-insensitive substring
            // match against each row's title and subtitle; non-matching
            // rows skip themselves via [LocalSettingsHubQuery].
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.settings_hub_search_label)) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.settings_hub_clear_search))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            // Issue #1624 — grouped hub. Each [HubGroup] renders a labelled
            // [SectionHeading] + [SettingsGroupCard], and hides itself when the
            // query matches none of its rows (so no orphan headings). Rows
            // still self-hide individually via [LocalSettingsHubQuery].
            CompositionLocalProvider(LocalSettingsHubQuery provides query) {
                HubGroup(
                    query = query,
                    heading = stringResource(R.string.settings_hub_group_voice_audio),
                    icon = Icons.Outlined.RecordVoiceOver,
                    sections = voiceAudioSections,
                ) {
                    SettingsHubRow(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = stringResource(R.string.settings_hub_voice_playback_title),
                        subtitle = stringResource(R.string.settings_hub_voice_playback_subtitle),
                        onClick = onOpenVoicePlayback,
                        keywords = HubKeywords.voicePlayback,
                    )
                    // #1624 — distinct glyph (was RecordVoiceOver, colliding
                    // with Voice & Playback above).
                    SettingsHubRow(
                        icon = Icons.Outlined.GraphicEq,
                        title = stringResource(R.string.settings_hub_voice_library_title),
                        subtitle = stringResource(R.string.settings_hub_voice_library_subtitle),
                        onClick = onOpenVoiceLibrary,
                        keywords = HubKeywords.voiceLibrary,
                    )
                    // #1624 — Cloud Voices, newly exposed (subscreen + route
                    // shipped, but no hub row until now; Azure key was only
                    // reachable via Plugins → Azure → Configure).
                    SettingsHubRow(
                        icon = Icons.Outlined.CloudSync,
                        title = stringResource(R.string.settings_hub_cloud_voices_title),
                        subtitle = stringResource(R.string.settings_hub_cloud_voices_subtitle),
                        onClick = onOpenCloudVoices,
                        keywords = HubKeywords.cloudVoices,
                    )
                    // #1624 — distinct glyph (was LibraryBooks, colliding with
                    // Bookshare + All settings).
                    SettingsHubRow(
                        icon = Icons.Outlined.Spellcheck,
                        title = stringResource(R.string.settings_hub_pronunciation_title),
                        subtitle = stringResource(R.string.settings_hub_pronunciation_subtitle),
                        onClick = onOpenPronunciationDict,
                        keywords = HubKeywords.pronunciation,
                    )
                }
                HubGroup(
                    query = query,
                    heading = stringResource(R.string.settings_hub_group_reading_display),
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    sections = readingDisplaySections,
                ) {
                    SettingsHubRow(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = stringResource(R.string.settings_hub_reading_title),
                        subtitle = stringResource(R.string.settings_hub_reading_subtitle),
                        onClick = onOpenReading,
                        keywords = HubKeywords.reading,
                    )
                    SettingsHubRow(
                        icon = Icons.Outlined.Palette,
                        title = stringResource(R.string.settings_hub_appearance_title),
                        subtitle = stringResource(R.string.settings_hub_appearance_subtitle),
                        onClick = onOpenAppearance,
                        keywords = HubKeywords.appearance,
                    )
                    SettingsHubRow(
                        icon = Icons.Outlined.Accessibility,
                        title = stringResource(R.string.settings_hub_accessibility_title),
                        subtitle = stringResource(R.string.settings_hub_accessibility_subtitle),
                        onClick = onOpenAccessibility,
                        keywords = HubKeywords.accessibility,
                    )
                }
                HubGroup(
                    query = query,
                    heading = stringResource(R.string.settings_hub_group_content_sources),
                    icon = Icons.Outlined.Extension,
                    sections = contentSourcesSections,
                ) {
                    // #1630 — Content Sources: per-source keys/tokens/URLs,
                    // un-buried from the legacy monolith into a dedicated
                    // subscreen (renders the generic sourceConfigSections seam).
                    SettingsHubRow(
                        icon = Icons.Outlined.Key,
                        title = stringResource(R.string.settings_hub_content_sources_title),
                        subtitle = stringResource(R.string.settings_hub_content_sources_subtitle),
                        onClick = onOpenContentSources,
                        keywords = HubKeywords.contentSources,
                    )
                    SettingsHubRow(
                        icon = Icons.Outlined.Extension,
                        title = stringResource(R.string.settings_hub_plugins_title),
                        subtitle = stringResource(R.string.settings_hub_plugins_subtitle),
                        onClick = onOpenPluginManager,
                        keywords = HubKeywords.plugins,
                    )
                    SettingsHubRow(
                        icon = Icons.Outlined.AccountCircle,
                        title = stringResource(R.string.settings_hub_account_title),
                        subtitle = stringResource(R.string.settings_hub_account_subtitle),
                        onClick = onOpenAccount,
                        keywords = HubKeywords.account,
                    )
                    SettingsHubRow(
                        icon = Icons.Outlined.Cloud,
                        title = stringResource(R.string.settings_hub_memory_palace_title),
                        subtitle = stringResource(R.string.settings_hub_memory_palace_subtitle),
                        onClick = onOpenMemoryPalace,
                        keywords = HubKeywords.memoryPalace,
                    )
                    // Bookshare keeps LibraryBooks — now unique after Pronunciation
                    // + All settings moved off it (#1624).
                    SettingsHubRow(
                        icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                        title = stringResource(R.string.settings_hub_bookshare_title),
                        subtitle = stringResource(R.string.settings_hub_bookshare_subtitle),
                        onClick = onOpenBookshare,
                        keywords = HubKeywords.bookshare,
                    )
                }
                // #1632 — Downloads & Storage (group 4, between Content &
                // Sources and AI per the #1624 IA; Notifications slots in
                // after this when #1631 lands).
                HubGroup(
                    query = query,
                    heading = stringResource(R.string.settings_hub_group_downloads),
                    icon = Icons.Outlined.Download,
                    sections = downloadsStorageSections,
                ) {
                    SettingsHubRow(
                        icon = Icons.Outlined.Download,
                        title = stringResource(R.string.settings_hub_downloads_title),
                        subtitle = stringResource(R.string.settings_hub_downloads_subtitle),
                        onClick = onOpenDownloads,
                        keywords = HubKeywords.downloadsStorage,
                    )
                }
                // #1631 — Notifications (group 5, after Downloads & Storage per
                // the approved 8-group mockup: …Content & Sources · Downloads &
                // Storage · Notifications · AI…).
                HubGroup(
                    query = query,
                    heading = stringResource(R.string.settings_hub_group_notifications),
                    icon = Icons.Outlined.Notifications,
                    sections = notificationsSections,
                ) {
                    SettingsHubRow(
                        icon = Icons.Outlined.Notifications,
                        title = stringResource(R.string.settings_hub_notifications_title),
                        subtitle = stringResource(R.string.settings_hub_notifications_subtitle),
                        onClick = onOpenNotifications,
                        keywords = HubKeywords.notifications,
                    )
                }
                HubGroup(
                    query = query,
                    heading = stringResource(R.string.settings_hub_group_ai),
                    icon = Icons.Outlined.AutoAwesome,
                    sections = aiSections,
                ) {
                    SettingsHubRow(
                        icon = Icons.Outlined.AutoAwesome,
                        title = stringResource(R.string.settings_hub_ai_title),
                        subtitle = stringResource(R.string.settings_hub_ai_subtitle),
                        onClick = onOpenAi,
                        keywords = HubKeywords.ai,
                    )
                    SettingsHubRow(
                        icon = Icons.Outlined.AutoStories,
                        title = stringResource(R.string.settings_hub_ai_sessions_title),
                        subtitle = stringResource(R.string.settings_hub_ai_sessions_subtitle),
                        onClick = onOpenAiSessions,
                        keywords = HubKeywords.aiSessions,
                    )
                }
                HubGroup(
                    query = query,
                    heading = stringResource(R.string.settings_hub_group_tools),
                    icon = Icons.Outlined.Insights,
                    sections = toolsSections,
                ) {
                    SettingsHubRow(
                        icon = Icons.Outlined.Insights,
                        title = stringResource(R.string.settings_hub_stats_title),
                        subtitle = stringResource(R.string.settings_hub_stats_subtitle),
                        onClick = onOpenStats,
                        keywords = HubKeywords.stats,
                    )
                    // #1624 — Morning Briefing copy extracted from inline
                    // hardcoded strings to resources (EN + ES) — the last
                    // hub row that wasn't localised.
                    SettingsHubRow(
                        icon = Icons.Outlined.Podcasts,
                        title = stringResource(R.string.settings_hub_briefing_title),
                        subtitle = stringResource(R.string.settings_hub_briefing_subtitle),
                        onClick = onOpenBriefing,
                        keywords = HubKeywords.briefing,
                    )
                    SettingsHubRow(
                        icon = Icons.Outlined.Description,
                        title = stringResource(R.string.settings_hub_scripts_title),
                        subtitle = stringResource(R.string.settings_hub_scripts_subtitle),
                        onClick = onOpenScripts,
                        keywords = HubKeywords.scripts,
                    )
                    // #1634 — Benefits suite (Screener/Decoder) re-discovery: a
                    // hub row for users who dismissed the Library hero card.
                    // Links the existing TECHEMPOWER_HOME route (no new screen).
                    SettingsHubRow(
                        icon = Icons.Outlined.VolunteerActivism,
                        title = stringResource(R.string.settings_hub_benefits_title),
                        subtitle = stringResource(R.string.settings_hub_benefits_subtitle),
                        onClick = onOpenBenefits,
                        keywords = HubKeywords.benefits,
                    )
                }
                HubGroup(
                    query = query,
                    heading = stringResource(R.string.settings_hub_group_system),
                    icon = Icons.Outlined.Speed,
                    sections = systemSections,
                ) {
                    SettingsHubRow(
                        icon = Icons.Outlined.Speed,
                        title = stringResource(R.string.settings_hub_performance_title),
                        subtitle = stringResource(R.string.settings_hub_performance_subtitle),
                        onClick = onOpenPerformance,
                        keywords = HubKeywords.performance,
                    )
                    SettingsHubRow(
                        icon = Icons.Outlined.Tune,
                        title = stringResource(R.string.settings_hub_advanced_title),
                        subtitle = stringResource(R.string.settings_hub_advanced_subtitle),
                        onClick = onOpenAdvanced,
                        keywords = HubKeywords.advanced,
                    )
                    SettingsHubRow(
                        icon = Icons.Outlined.BugReport,
                        title = stringResource(R.string.settings_hub_developer_title),
                        subtitle = stringResource(R.string.settings_hub_developer_subtitle),
                        onClick = onOpenDebug,
                        keywords = HubKeywords.developer,
                    )
                }
                HubGroup(
                    query = query,
                    heading = stringResource(R.string.settings_hub_group_about),
                    icon = Icons.Outlined.Info,
                    sections = aboutSections,
                ) {
                    SettingsHubRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.settings_hub_about_title),
                        subtitle = stringResource(R.string.settings_hub_about_subtitle),
                        onClick = onOpenAbout,
                        keywords = HubKeywords.about,
                    )
                }
                // Escape hatch — the legacy flat-scroll SettingsScreen still
                // works; it trails below a divider so it doesn't compete with
                // the curated groups (#440). Self-hides on a non-matching query
                // like any other row; distinct Toc glyph (was LibraryBooks).
                if (matchesHubQuery(
                        query,
                        escapeHatchSection.title,
                        escapeHatchSection.subtitle,
                        escapeHatchSection.keywords,
                    )
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsGroupCard {
                        SettingsHubRow(
                            icon = Icons.Outlined.Toc,
                            title = stringResource(R.string.settings_hub_all_settings_title),
                            subtitle = stringResource(R.string.settings_hub_all_settings_subtitle),
                            onClick = onOpenAllSettings,
                            keywords = HubKeywords.allSettings,
                        )
                    }
                }
            }
            // #1160: rows self-hide on a non-matching query, so a search with
            // no hits collapsed to a blank card with no feedback. Surface a
            // polite live-region "No results" line. SettingsHubSections is
            // test-pinned to mirror the rendered rows, so reusing matchesHubQuery
            // over it tracks visibility exactly; a blank query matches every
            // section, so this branch only fires when the user has typed.
            val hasResults = SettingsHubSections.any { matchesHubQuery(query, it.title, it.subtitle, it.keywords) }
            if (!hasResults) {
                Text(
                    text = stringResource(R.string.settings_hub_no_results, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/**
 * Issue #1624 — one labelled category on the hub: a [SectionHeading] (which
 * carries `heading()` semantics, so TalkBack heading-navigation lands on the
 * category name) above its own [SettingsGroupCard]. The whole group hides when
 * the current [query] matches none of its [sections], so search never leaves an
 * orphan heading over an empty card. Individual rows still self-hide via
 * [LocalSettingsHubQuery], so a partially-matching group shows only its
 * matching rows.
 */
@Composable
private fun HubGroup(
    query: String,
    heading: String,
    icon: ImageVector,
    sections: List<SettingsHubSection>,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (sections.none { matchesHubQuery(query, it.title, it.subtitle, it.keywords) }) return
    SectionHeading(label = heading, icon = icon)
    SettingsGroupCard(content = content)
}

/**
 * A hub link row. Shaped like [SettingsLinkRow] but with a brass-tinted
 * leading icon — wraps the [SettingsRow] primitive directly so we don't
 * have to widen [SettingsLinkRow]'s contract for the hub.
 *
 * Visible to [SettingsHubScreenSmokeTest] (`internal`) so the test can
 * count rows and assert their click handlers all wire through.
 */
@Composable
internal fun SettingsHubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    /**
     * Issue #1577 — non-obvious search synonyms + the names of the settings
     * this row leads to, so hub search can surface a row for a knob buried in
     * its subscreen (e.g. "dnd" → Voice & Playback). Empty for rows whose
     * title/subtitle already cover every term a user would type.
     */
    keywords: List<String> = emptyList(),
) {
    // Issue #773 / #1577 — search filter. When [LocalSettingsHubQuery] is
    // non-blank and matches neither title, subtitle, nor any keyword, the row
    // skips itself so the hub collapses to just the matches.
    if (!matchesHubQuery(LocalSettingsHubQuery.current, title, subtitle, keywords)) return
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailing = {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * Issue #440 — the canonical Settings hub row catalog. Tests pin this
 * list so an accidental reorder or removal of a row in
 * [SettingsHubScreen] surfaces here first.
 *
 * The list is constructed lazily because the icon objects live in the
 * Compose runtime classloader; consumers either iterate it (tests) or
 * use it as documentation of the hub's intended shape. The actual
 * rendering in [SettingsHubScreen] mirrors this list manually, since
 * row-specific click handlers don't fit cleanly into a data class.
 *
 * Order matches the row order in [SettingsHubScreen]'s kdoc.
 */
data class SettingsHubSection(
    val title: String,
    val subtitle: String,
    /** Issue #1577 — hub search synonyms; see [SettingsHubRow.keywords]. */
    val keywords: List<String> = emptyList(),
)

/**
 * Issue #1577 — the hub-search keyword lists, defined once and referenced by
 * BOTH the rendered [SettingsHubRow]s (so a row self-shows on a keyword hit)
 * AND the [SettingsHubSections] catalog (so the "No results" line agrees with
 * what's actually on screen). Keeping them in one place is what stops the two
 * from drifting apart. Terms already present in a row's title/subtitle are not
 * repeated here — [matchesHubQuery] scans all three.
 */
internal object HubKeywords {
    // The flagship: sleep-timer + Do-Not-Disturb settings live in Voice &
    // Playback (#1574). Every term a user might reach for lands them here.
    val voicePlayback = listOf(
        "sleep", "sleep timer", "bedtime", "dnd", "do not disturb", "timer",
        "shake", "skip", "rewind", "speed", "pitch", "cadence", "punctuation",
        "pause", "tts", "language", "pronunciation", "narration",
    )
    val voiceLibrary = listOf("voice", "narrator", "speaker", "download", "kokoro", "piper", "azure")
    // Issue #1624 — Cloud Voices (Azure HD / Dragon HD BYOK). Title/subtitle
    // already carry "cloud", "azure", "hd", "dragon"; add the non-obvious.
    val cloudVoices = listOf("neural", "byok", "region", "tts key", "fallback", "cloud voice")
    val reading = listOf(
        "font", "text size", "dyslexic", "opendyslexic", "sepia", "cream",
        "highlight", "karaoke", "theme", "dark", "light", "focus", "auto-scroll",
        "autoscroll", "typography", "colour", "color", "line spacing", "contrast",
    )
    val stats = listOf("stats", "streak", "history", "finished", "time listened", "progress")
    val briefing = listOf("briefing", "podcast", "digest", "morning", "episode", "queue")
    val scripts = listOf("teleprompter", "script", "prompter", "rehearsal", "wpm", "words per minute")
    val benefits = listOf(
        "benefits", "qualify", "screener", "decoder", "calfresh", "medi-cal",
        "snap", "assistance", "aid", "notice", "letter", "techempower",
    )
    val appearance = listOf(
        "cover", "monogram", "animation", "motion", "particle", "confetti",
        "skeleton", "shimmer", "brass pulse", "theme",
    )
    val performance = listOf(
        "buffer", "cache", "prerender", "pre-render", "network", "patience",
        "warm-up", "warmup", "catch-up", "determinism", "threads", "parallel synth",
    )
    val ai = listOf(
        "chat", "model", "claude", "openai", "gpt", "ollama", "vertex", "bedrock",
        "foundry", "recap", "grounding", "api key",
    )
    val accessibility = listOf(
        "talkback", "contrast", "reduced motion", "font scale", "touch target",
        "screen reader", "reading direction", "dyslexia", "a11y",
    )
    val aiSessions = listOf("sessions", "chats", "history", "delete history")
    val plugins = listOf(
        "sources", "backends", "voice families", "voice bundles", "enable", "disable",
        "reddit", "notion", "prime gaming", "slack", "matrix", "discord", "telegram",
    )
    // #1630 — Content Sources aggregates every credentialed source's config.
    val contentSources = listOf(
        "source config", "credentials", "token", "api key", "discord", "telegram",
        "outline", "epub", "pdf", "wikipedia", "google news", "reddit", "notion",
        "slack", "matrix", "prime gaming", "feed url",
    )
    val pronunciation = listOf("pronunciation", "phonetic", "lexicon", "word override", "ipa")
    val account = listOf("royal road", "github", "sign in", "login", "cloud sync", "oauth")
    val memoryPalace = listOf("mempalace", "memory palace", "daemon", "host", "lan", "probe")
    val bookshare = listOf("bookshare", "daisy", "accessible", "partner", "api key")
    val downloadsStorage = listOf(
        "download", "downloads", "storage", "cache", "offline", "wifi", "wi-fi",
        "data saver", "unmetered", "clear cache", "quota", "update interval", "poll",
    )
    // #1631 — Notifications: new-chapter alerts + system permission.
    val notifications = listOf(
        "notification", "notifications", "alert", "alerts", "new chapter", "push",
        "inbox", "royal road", "kvmr", "wikipedia", "permission", "system settings",
    )
    val advanced = listOf("android auto", "car", "items per category", "integration")
    val developer = listOf("debug", "overlay", "log", "diagnostics", "reset onboarding", "verbose")
    val about = listOf(
        "version", "sigil", "build", "license", "open source", "handbook", "help",
        "guide", "manual", "user guide", "privacy", "impact sharing", "accessibility statement",
        "report content", "welcome", "tour", "replay",
    )
    val allSettings = listOf("everything", "all", "legacy", "full list")
}

// Issue #1624 — the catalog, partitioned into the seven rendered groups. Each
// group's list drives BOTH the [HubGroup] "hide when no row matches" check and
// (concatenated below) the flat [SettingsHubSections] the "No results" line +
// tests read. The composable mirrors these rows manually (with real click
// handlers). Subtitles here must match the rendered stringResource values so
// search behaves identically over catalog and screen.
internal val voiceAudioSections = listOf(
    SettingsHubSection("Voice & Playback", "Voice, speed, sleep timer, Do Not Disturb.", HubKeywords.voicePlayback),
    SettingsHubSection("Voice library", "Browse and switch between available voices.", HubKeywords.voiceLibrary),
    // #1624 — newly exposed (subscreen shipped, no hub row before).
    SettingsHubSection("Cloud Voices", "Azure HD & Dragon HD — bring your own key.", HubKeywords.cloudVoices),
    SettingsHubSection("Pronunciation dictionary", "Per-word phonetic overrides.", HubKeywords.pronunciation),
)
internal val readingDisplaySections = listOf(
    SettingsHubSection("Reading", "Theme, fonts, colours, highlight, focus.", HubKeywords.reading),
    SettingsHubSection("Appearance", "Book cover style, animation, particles.", HubKeywords.appearance),
    SettingsHubSection("Accessibility", "TalkBack, contrast, motion, font scale.", HubKeywords.accessibility),
)
internal val contentSourcesSections = listOf(
    // #1630 — Content Sources subscreen (per-source keys/tokens/URLs). Subtitle
    // matches the rendered R.string.settings_hub_content_sources_subtitle.
    SettingsHubSection("Content Sources", "Per-source keys, tokens, and options.", HubKeywords.contentSources),
    SettingsHubSection("Plugins", "Toggle backends — Fiction, Audio streams, Voice bundles.", HubKeywords.plugins),
    SettingsHubSection("Account", "Royal Road, GitHub.", HubKeywords.account),
    SettingsHubSection("Memory Palace", "Daemon host, probe, integration.", HubKeywords.memoryPalace),
    SettingsHubSection("Bookshare", "Accessible DAISY library · partner API key.", HubKeywords.bookshare),
)
// #1632 — Downloads & Storage (group 4). Subtitle matches the rendered
// R.string.settings_hub_downloads_subtitle.
internal val downloadsStorageSections = listOf(
    SettingsHubSection(
        "Downloads & Storage",
        "Default download mode, Wi-Fi, update interval, cache.",
        HubKeywords.downloadsStorage,
    ),
)
// #1631 — Notifications (group 5). Subtitle matches R.string.settings_hub_notifications_subtitle.
internal val notificationsSections = listOf(
    SettingsHubSection(
        "Notifications",
        "New-chapter alerts and system permission.",
        HubKeywords.notifications,
    ),
)
internal val aiSections = listOf(
    SettingsHubSection("AI", "Chat model, grounding, recap.", HubKeywords.ai),
    SettingsHubSection("AI sessions", "Review past chats and delete history.", HubKeywords.aiSessions),
)
internal val toolsSections = listOf(
    SettingsHubSection("Listening stats", "Time listened, streaks, books finished.", HubKeywords.stats),
    SettingsHubSection("Morning Briefing", "One episode from your sources — HN, arXiv, RSS, GitHub.", HubKeywords.briefing),
    SettingsHubSection("Scripts", "Save, edit, and organize teleprompter scripts.", HubKeywords.scripts),
    // #1634 — Benefits re-discovery. Subtitle matches R.string.settings_hub_benefits_subtitle.
    SettingsHubSection("Benefits", "Do-I-qualify screener & letter decoder.", HubKeywords.benefits),
)
internal val systemSections = listOf(
    SettingsHubSection("Performance", "Buffer, parallel synth, decoder choice.", HubKeywords.performance),
    SettingsHubSection("Advanced", "Android Auto, integration tunables.", HubKeywords.advanced),
    SettingsHubSection("Developer", "Debug overlay, log ring, advanced toggles.", HubKeywords.developer),
)
internal val aboutSections = listOf(
    SettingsHubSection("About", "Version, sigil, handbook, privacy, licenses.", HubKeywords.about),
)
// Escape hatch — rendered last, below a divider, outside the groups.
internal val escapeHatchSection =
    SettingsHubSection("All settings", "Every setting on one long page (legacy).", HubKeywords.allSettings)

val SettingsHubSections: List<SettingsHubSection> =
    voiceAudioSections +
        readingDisplaySections +
        contentSourcesSections +
        downloadsStorageSections +
        notificationsSections +
        aiSections +
        toolsSections +
        systemSections +
        aboutSections +
        escapeHatchSection
