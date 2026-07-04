package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
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
 * The hub is the new gear-icon destination: a short list of section
 * cards, each carrying a one-line subtitle that previews its
 * contents and routes to a dedicated subscreen:
 *
 *  - Voice & Playback → [VoiceAndPlaybackSettingsScreen]
 *  - Voice library → [VoiceLibraryScreen][in.jphe.storyvox.feature.voicelibrary.VoiceLibraryScreen]
 *  - Reading → [ReadingSettingsScreen]
 *  - Listening stats → [ListeningStatsScreen][in.jphe.storyvox.feature.stats.ListeningStatsScreen]
 *  - Performance → [PerformanceSettingsScreen]
 *  - AI → [AiSettingsScreen]
 *  - Accessibility → [AccessibilitySettingsScreen] (Phase 1 scaffold)
 *  - AI sessions → [SessionsScreen][in.jphe.storyvox.feature.sessions.SessionsScreen]
 *  - Plugins → [PluginManagerScreen][in.jphe.storyvox.feature.settings.plugins.PluginManagerScreen]
 *  - Pronunciation dictionary → [PronunciationDictScreen][in.jphe.storyvox.feature.settings.pronunciation.PronunciationDictScreen]
 *  - Account → [AccountSettingsScreen]
 *  - Memory Palace → [MemoryPalaceSettingsScreen]
 *  - Developer → [DebugScreen][in.jphe.storyvox.feature.debug.DebugScreen]
 *  - About → [AboutSettingsScreen]
 *
 * The long [SettingsScreen] is preserved as an "All settings" escape
 * hatch for power users who want everything on one searchable page;
 * a dedicated row at the bottom of the hub routes there explicitly
 * so the affordance isn't lost.
 *
 * ## Section row order
 *
 * Most-touched first (matches the section ribbon order in
 * [SettingsScreen]):
 *
 * 1. Voice & Playback — voice, speed, cadence, pitch.
 * 2. Voice library — dedicated subscreen.
 * 3. Reading — theme, sleep timer.
 * 4. Performance — buffering, parallel synth, decoder choice.
 * 5. AI — chat model, grounding, recap.
 * 6. Accessibility — TalkBack / Switch Access scaffolding (Phase 1).
 * 7. AI sessions — dedicated subscreen.
 * 8. Plugins — registry-driven plugin manager (#404 surface).
 * 9. Pronunciation dictionary — dedicated subscreen.
 * 10. Account — Royal Road / GitHub sign-ins.
 * 11. Memory Palace — daemon host config + probe.
 * 12. Developer — Debug screen + advanced toggles.
 * 13. About — version sigil + open-source notices.
 * 14. All settings (legacy long page) — escape hatch.
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
            // The hub renders as a single brass-edged group card. Same
            // brass surface as the rest of Settings — SettingsGroupCard
            // wraps Card(surfaceContainerHigh, shapes.large) and a 1-dp
            // inter-row peek. One card with many link rows reads as a
            // navigation index rather than a fragmented card grid.
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
            CompositionLocalProvider(LocalSettingsHubQuery provides query) {
            SettingsGroupCard {
                // Voice & Playback — most-touched, first.
                SettingsHubRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = stringResource(R.string.settings_hub_voice_playback_title),
                    subtitle = stringResource(R.string.settings_hub_voice_playback_subtitle),
                    onClick = onOpenVoicePlayback,
                    keywords = HubKeywords.voicePlayback,
                )
                // Voice library — dedicated subscreen.
                SettingsHubRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = stringResource(R.string.settings_hub_voice_library_title),
                    subtitle = stringResource(R.string.settings_hub_voice_library_subtitle),
                    onClick = onOpenVoiceLibrary,
                    keywords = HubKeywords.voiceLibrary,
                )
                SettingsHubRow(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = stringResource(R.string.settings_hub_reading_title),
                    subtitle = stringResource(R.string.settings_hub_reading_subtitle),
                    onClick = onOpenReading,
                    keywords = HubKeywords.reading,
                )
                // Issue #1235 — Listening stats dashboard. Sits next to
                // Reading: both are about the user's own reading, one the
                // knobs, the other the retrospective.
                SettingsHubRow(
                    icon = Icons.Outlined.Insights,
                    title = stringResource(R.string.settings_hub_stats_title),
                    subtitle = stringResource(R.string.settings_hub_stats_subtitle),
                    onClick = onOpenStats,
                    keywords = HubKeywords.stats,
                )
                // Issue #1467 — morning briefing / personal-podcast queue.
                // Reading-adjacent: a curated listen-through of your sources'
                // latest, stitched into one hands-free episode. (Copy inline
                // for slice 1; string extraction is a follow-up.)
                SettingsHubRow(
                    icon = Icons.Outlined.Podcasts,
                    title = "Morning Briefing",
                    subtitle = "One episode from your sources — HN, arXiv, RSS, GitHub",
                    onClick = onOpenBriefing,
                    keywords = HubKeywords.briefing,
                )
                // Issue #1369 — teleprompter script manager. Reading-adjacent:
                // save/edit/organize scripts the teleprompter can load.
                SettingsHubRow(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.settings_hub_scripts_title),
                    subtitle = stringResource(R.string.settings_hub_scripts_subtitle),
                    onClick = onOpenScripts,
                    keywords = HubKeywords.scripts,
                )
                // v0.5.59 (#cover-style-toggle) — Appearance. Book-
                // cover fallback style (Monogram / Branded / Cover
                // only). Sits next to Reading because both are
                // visual-style knobs; future visual rows land here.
                SettingsHubRow(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.settings_hub_appearance_title),
                    subtitle = stringResource(R.string.settings_hub_appearance_subtitle),
                    onClick = onOpenAppearance,
                    keywords = HubKeywords.appearance,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.Speed,
                    title = stringResource(R.string.settings_hub_performance_title),
                    subtitle = stringResource(R.string.settings_hub_performance_subtitle),
                    onClick = onOpenPerformance,
                    keywords = HubKeywords.performance,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.settings_hub_ai_title),
                    subtitle = stringResource(R.string.settings_hub_ai_subtitle),
                    onClick = onOpenAi,
                    keywords = HubKeywords.ai,
                )
                // Accessibility — Phase 1 scaffold (v0.5.42). Positioned
                // between AI and AI sessions per spec: user-facing tier,
                // not buried with the advanced rows further down.
                SettingsHubRow(
                    icon = Icons.Outlined.Accessibility,
                    title = stringResource(R.string.settings_hub_accessibility_title),
                    subtitle = stringResource(R.string.settings_hub_accessibility_subtitle),
                    onClick = onOpenAccessibility,
                    keywords = HubKeywords.accessibility,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.AutoStories,
                    title = stringResource(R.string.settings_hub_ai_sessions_title),
                    subtitle = stringResource(R.string.settings_hub_ai_sessions_subtitle),
                    onClick = onOpenAiSessions,
                    keywords = HubKeywords.aiSessions,
                )
                // Plugin manager (#404). Dedicated subscreen with its own
                // search + filter chips + capability legend.
                SettingsHubRow(
                    icon = Icons.Outlined.Extension,
                    title = stringResource(R.string.settings_hub_plugins_title),
                    subtitle = stringResource(R.string.settings_hub_plugins_subtitle),
                    onClick = onOpenPluginManager,
                    keywords = HubKeywords.plugins,
                )
                SettingsHubRow(
                    icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                    title = stringResource(R.string.settings_hub_pronunciation_title),
                    subtitle = stringResource(R.string.settings_hub_pronunciation_subtitle),
                    onClick = onOpenPronunciationDict,
                    keywords = HubKeywords.pronunciation,
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
                // Issue #1471 — Bookshare partner-key entry. Source-
                // credential subscreen, adjacent to Memory Palace (both
                // configure a source's access).
                SettingsHubRow(
                    icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                    title = stringResource(R.string.settings_hub_bookshare_title),
                    subtitle = stringResource(R.string.settings_hub_bookshare_subtitle),
                    onClick = onOpenBookshare,
                    keywords = HubKeywords.bookshare,
                )
                // v1 settings-bundle-7 — Advanced subscreen. Power-
                // user knobs (Android Auto bucket size, future
                // integration tunables). Sits next to Developer
                // because both are infrequently-touched surfaces;
                // Advanced is user-facing while Developer is for
                // debugging.
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
                SettingsHubRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.settings_hub_about_title),
                    subtitle = stringResource(R.string.settings_hub_about_subtitle),
                    onClick = onOpenAbout,
                    keywords = HubKeywords.about,
                )
                // Escape hatch — the legacy flat-scroll SettingsScreen
                // still works; users who want the old experience reach
                // it via this row. Subtitle pre-empts confusion: "yes,
                // everything you used to scroll through is still here".
                SettingsHubRow(
                    icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                    title = stringResource(R.string.settings_hub_all_settings_title),
                    subtitle = stringResource(R.string.settings_hub_all_settings_subtitle),
                    onClick = onOpenAllSettings,
                    keywords = HubKeywords.allSettings,
                )
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
    val reading = listOf(
        "font", "text size", "dyslexic", "opendyslexic", "sepia", "cream",
        "highlight", "karaoke", "theme", "dark", "light", "focus", "auto-scroll",
        "autoscroll", "typography", "colour", "color", "line spacing", "contrast",
    )
    val stats = listOf("stats", "streak", "history", "finished", "time listened", "progress")
    val briefing = listOf("briefing", "podcast", "digest", "morning", "episode", "queue")
    val scripts = listOf("teleprompter", "script", "prompter", "rehearsal", "wpm", "words per minute")
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
        "reddit", "notion", "prime gaming",
    )
    val pronunciation = listOf("pronunciation", "phonetic", "lexicon", "word override", "ipa")
    val account = listOf("royal road", "github", "sign in", "login", "cloud sync", "oauth")
    val memoryPalace = listOf("mempalace", "memory palace", "daemon", "host", "lan", "probe")
    val bookshare = listOf("bookshare", "daisy", "accessible", "partner", "api key")
    val advanced = listOf("android auto", "car", "items per category", "integration")
    val developer = listOf("debug", "overlay", "log", "diagnostics", "reset onboarding", "verbose")
    val about = listOf(
        "version", "sigil", "build", "license", "open source", "handbook", "help",
        "guide", "manual", "user guide", "privacy", "impact sharing", "accessibility statement",
        "report content", "welcome", "tour", "replay",
    )
    val allSettings = listOf("everything", "all", "legacy", "full list")
}

val SettingsHubSections: List<SettingsHubSection> = listOf(
    SettingsHubSection("Voice & Playback", "Voice, speed, sleep timer, Do Not Disturb.", HubKeywords.voicePlayback),
    SettingsHubSection("Voice library", "Browse and switch between available voices.", HubKeywords.voiceLibrary),
    SettingsHubSection("Reading", "Theme, fonts, colours, highlight, focus.", HubKeywords.reading),
    // Issue #1235 — Listening stats dashboard.
    SettingsHubSection("Listening stats", "Time listened, streaks, books finished.", HubKeywords.stats),
    // Issue #1467 — morning briefing / personal-podcast queue. Rendered inline
    // in the composable; catalogued here (#1577) so search + "No results" agree.
    SettingsHubSection("Morning Briefing", "One episode from your sources — HN, arXiv, RSS, GitHub.", HubKeywords.briefing),
    // Issue #1369 — teleprompter script manager. Also rendered inline.
    SettingsHubSection("Scripts", "Save, edit, and organize teleprompter scripts.", HubKeywords.scripts),
    // v0.5.59 (#cover-style-toggle) — Appearance.
    SettingsHubSection("Appearance", "Book cover style, animation, particles.", HubKeywords.appearance),
    SettingsHubSection("Performance", "Buffer, parallel synth, decoder choice.", HubKeywords.performance),
    SettingsHubSection("AI", "Chat model, grounding, recap.", HubKeywords.ai),
    // Phase 1 scaffold — v0.5.42. Phase 2 wires the actual behavior.
    SettingsHubSection("Accessibility", "TalkBack, contrast, motion, font scale.", HubKeywords.accessibility),
    SettingsHubSection("AI sessions", "Review past chats and delete history.", HubKeywords.aiSessions),
    SettingsHubSection("Plugins", "Toggle backends — Fiction, Audio streams, Voice bundles.", HubKeywords.plugins),
    SettingsHubSection("Pronunciation dictionary", "Per-word phonetic overrides.", HubKeywords.pronunciation),
    SettingsHubSection("Account", "Royal Road, GitHub.", HubKeywords.account),
    SettingsHubSection("Memory Palace", "Daemon host, probe, integration.", HubKeywords.memoryPalace),
    // Issue #1471 — Bookshare partner-key entry. Rendered inline; catalogued (#1577).
    SettingsHubSection("Bookshare", "Accessible DAISY library · partner API key.", HubKeywords.bookshare),
    SettingsHubSection("Advanced", "Android Auto, integration tunables.", HubKeywords.advanced),
    SettingsHubSection("Developer", "Debug overlay, log ring, advanced toggles.", HubKeywords.developer),
    SettingsHubSection("About", "Version, sigil, handbook, privacy, licenses.", HubKeywords.about),
    SettingsHubSection("All settings", "Every setting on one long page (legacy).", HubKeywords.allSettings),
)
