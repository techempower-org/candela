package `in`.jphe.storyvox.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.browse.glyphByName
import `in`.jphe.storyvox.feature.browse.sourceGlyph
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Issue #1370 — third of the (now four) first-launch welcome screens,
 * inserted between [VoicePickerOnboarding] and [FirstFictionPicker].
 *
 * The voice picker answers "how should it sound"; this screen answers
 * "what do you want to read". A brand-new user otherwise lands on Browse
 * with whatever the default-enabled backends happen to be, with no idea
 * the catalog spans 25+ sources. Surfacing the roster here — grouped
 * into five plain-English buckets, each row a one-tap toggle — turns
 * source discovery into an opt-in moment instead of a Settings dig.
 *
 * Nothing here is destructive or committing: every toggle writes through
 * to [SettingsRepositoryUi.setSourcePluginEnabled] immediately (same
 * persistence path as the full Plugin Manager), so "Continue" and "Skip"
 * are both pure navigation — Skip simply means "I didn't change the
 * defaults". The user can revisit every choice in Settings → Plugins,
 * which the subtitle says out loud.
 *
 * Visual style mirrors [VoicePickerOnboarding]: serif brass headline,
 * a quiet subtitle, then a vertical scroll of brass-edged cards. The
 * shared dot-row step indicator is supplied by the [OnboardingScaffold]
 * the host wraps every step in, so this screen owns only its own body.
 */
@Composable
fun SourcePickerOnboarding(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    viewModel: SourcePickerOnboardingViewModel = hiltViewModel(),
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    SourcePickerOnboardingContent(
        sections = sections,
        onToggle = viewModel::toggle,
        onContinue = onContinue,
        onSkip = onSkip,
    )
}

@Composable
private fun SourcePickerOnboardingContent(
    sections: List<OnboardingSourceSection>,
    onToggle: (id: String, enabled: Boolean) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Clear the scaffold's step-indicator dot row at the top.
            Spacer(Modifier.height(spacing.xl))
            Text(
                stringResource(R.string.onboarding_sources_headline),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                stringResource(R.string.onboarding_sources_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 400.dp),
            )
            Spacer(Modifier.height(spacing.lg))

            sections.forEach { section ->
                SourceSectionHeader(titleRes = section.group.titleRes)
                Spacer(Modifier.height(spacing.sm))
                section.rows.forEach { row ->
                    SourceCard(row = row, onToggle = { onToggle(row.id, it) })
                    Spacer(Modifier.height(spacing.sm))
                }
                Spacer(Modifier.height(spacing.md))
            }

            BrassButton(
                label = stringResource(R.string.onboarding_sources_continue),
                onClick = onContinue,
                variant = BrassButtonVariant.Primary,
            )
            BrassButton(
                label = stringResource(R.string.onboarding_sources_skip),
                onClick = onSkip,
                variant = BrassButtonVariant.Text,
            )
            Spacer(Modifier.height(spacing.xl))
        }
    }
}

@Composable
private fun SourceSectionHeader(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp),
    )
}

/**
 * One source row — icon disc + name + one-line description + a brass
 * toggle. The whole row is the toggle target (there is no details sheet
 * to compete for the tap, unlike the Plugin Manager's card), so we hang
 * a single [Modifier.toggleable] with [Role.Switch] on the Row and leave
 * the [Switch]'s own `onCheckedChange` null. TalkBack then announces the
 * merged node once — "<name>, <description>, switch, on" — and Switch
 * Access gets one focus stop instead of two.
 */
@Composable
private fun SourceCard(
    row: SourcePickRow,
    onToggle: (Boolean) -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val brassColors = SwitchDefaults.colors(
        checkedThumbColor = brass,
        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
        checkedBorderColor = brass,
        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (row.enabled) brass else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .toggleable(
                value = row.enabled,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // #1527 — honor the source's declared @SourcePlugin(iconName)
                // here too, so a glyph declared without a feature edit lights up
                // in onboarding as well as the Browse carousel.
                imageVector = row.iconName.takeIf { it.isNotBlank() }?.let(::glyphByName)
                    ?: sourceGlyph(row.id),
                // Decorative — the row's merged semantics already speak
                // the source name, so a glyph description would just
                // double up for TalkBack.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (row.description.isNotBlank()) {
                Text(
                    row.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = row.enabled,
            // Row owns the toggle (see [SourceCard] kdoc) — null keeps
            // the Switch a non-interactive visual so it isn't a second
            // focus stop.
            onCheckedChange = null,
            colors = brassColors,
        )
    }
}

/**
 * Compose-facing projection of one registered source plugin for the
 * onboarding picker — the subset of [SourcePluginDescriptor] the screen
 * renders, plus the resolved enabled state.
 */
@Immutable
data class SourcePickRow(
    val id: String,
    val displayName: String,
    val description: String,
    val category: SourceCategory,
    val enabled: Boolean,
    /** `@SourcePlugin(iconName)` — the declared Browse glyph, resolved the same
     *  prefer-declared-then-fall-back-to-id way the carousel does (#1527). */
    val iconName: String = "",
)

/**
 * One rendered section of the picker — a friendly group plus the rows
 * that landed in it, in display order. Empty groups are dropped before
 * this point so the screen never renders a header with no cards.
 */
@Immutable
data class OnboardingSourceSection(
    val group: OnboardingSourceGroup,
    val rows: List<SourcePickRow>,
)

/**
 * The five plain-English buckets the onboarding picker groups sources
 * into, in display order. Deliberately coarser and friendlier than the
 * raw [SourceCategory] enum: most backends are technically
 * [SourceCategory.Text] (Royal Road, Wikipedia, RSS and the book
 * catalogs all are), which would collapse into one undifferentiated
 * 20-row wall. A brand-new user thinks in "books vs. news vs. my own
 * stuff", not "Text vs. Ebook", so we re-bucket by intent.
 */
enum class OnboardingSourceGroup(@StringRes val titleRes: Int) {
    Books(R.string.onboarding_sources_group_books),
    WebFiction(R.string.onboarding_sources_group_webfiction),
    News(R.string.onboarding_sources_group_news),
    YourContent(R.string.onboarding_sources_group_yourcontent),
    Audio(R.string.onboarding_sources_group_audio),
}

/**
 * Hand-curated id→bucket assignment. The codebase already curates
 * per-id tables this way (`sourceGlyph`, `friendlyVoiceSelection`), and
 * the trade-off is the same: a new backend that isn't listed here falls
 * through to [OnboardingSourceGroup.YourContent] — a safe, sensible
 * catch-all — rather than vanishing. Audio and explicitly-Ebook sources
 * are bucketed by [SourceCategory] (authoritative for those), so only
 * the big [SourceCategory.Text] family needs the by-intent split.
 */
private val BOOK_IDS: Set<String> = setOf(
    SourceIds.GUTENBERG,
    SourceIds.STANDARD_EBOOKS,
    SourceIds.EPUB,
    SourceIds.PDF,
)

private val WEB_FICTION_IDS: Set<String> = setOf(
    SourceIds.ROYAL_ROAD,
    SourceIds.AO3,
)

private val NEWS_IDS: Set<String> = setOf(
    SourceIds.WIKIPEDIA,
    SourceIds.WIKISOURCE,
    SourceIds.HACKERNEWS,
    SourceIds.ARXIV,
    SourceIds.PLOS,
    SourceIds.GOOGLE_NEWS,
    SourceIds.NOTION_TECHEMPOWER,
)

/** Which friendly bucket a single row belongs to. Category wins for the
 *  two unambiguous families (audio streams, EPUB-shaped catalogs); the
 *  rest is the curated by-intent split over the Text family. */
internal fun onboardingGroupOf(row: SourcePickRow): OnboardingSourceGroup = when {
    row.category == SourceCategory.AudioStream -> OnboardingSourceGroup.Audio
    row.category == SourceCategory.Ebook -> OnboardingSourceGroup.Books
    row.id in BOOK_IDS -> OnboardingSourceGroup.Books
    row.id in WEB_FICTION_IDS -> OnboardingSourceGroup.WebFiction
    row.id in NEWS_IDS -> OnboardingSourceGroup.News
    else -> OnboardingSourceGroup.YourContent
}

/**
 * Bucket [rows] into [OnboardingSourceSection]s in [OnboardingSourceGroup]
 * display order, alphabetised within each group and with empty groups
 * dropped. Pure so it can be unit-tested without Compose or Hilt.
 */
internal fun groupSourcesForOnboarding(
    rows: List<SourcePickRow>,
): List<OnboardingSourceSection> {
    val byGroup = rows.groupBy(::onboardingGroupOf)
    return OnboardingSourceGroup.entries.mapNotNull { group ->
        val groupRows = byGroup[group]?.sortedBy { it.displayName.lowercase() }
        if (groupRows.isNullOrEmpty()) null
        else OnboardingSourceSection(group = group, rows = groupRows)
    }
}

/**
 * Issue #1370 — drives [SourcePickerOnboarding]. Joins the live
 * [SourcePluginRegistry] roster with the user's per-plugin enabled map
 * (falling back to each descriptor's `defaultEnabled` for ids the user
 * hasn't touched), then groups for display. Toggling writes straight
 * through to settings — the same single-source-of-truth path the Plugin
 * Manager uses — so the picker holds no draft state of its own.
 */
@HiltViewModel
class SourcePickerOnboardingViewModel @Inject constructor(
    private val registry: SourcePluginRegistry,
    private val settings: SettingsRepositoryUi,
) : ViewModel() {

    val sections: StateFlow<List<OnboardingSourceSection>> = settings.settings
        .map { s ->
            val rows = registry.descriptors.map { descriptor ->
                SourcePickRow(
                    id = descriptor.id,
                    displayName = descriptor.displayName,
                    description = descriptor.description,
                    category = descriptor.category,
                    enabled = s.sourcePluginsEnabled[descriptor.id] ?: descriptor.defaultEnabled,
                    iconName = descriptor.iconName,
                )
            }
            groupSourcesForOnboarding(rows)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(id: String, enabled: Boolean) {
        viewModelScope.launch { settings.setSourcePluginEnabled(id, enabled) }
    }
}
