package `in`.jphe.storyvox.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.settings.AppLanguage
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1466 — the "English / Español" choice at the very top of the
 * first onboarding screen, before anything else asks the user to read.
 *
 * - The heading is bilingual on purpose ("Choose your language · Elige
 *   tu idioma") and never translated, so someone who can't read the
 *   current language still recognises their own.
 * - The two options are autonyms (reusing the #1585 Settings strings),
 *   side by side, full-width and at least 64dp tall — a big, obvious
 *   target for a first-time or motor-impaired user.
 * - Each option is a [BrassButton] with `selected`, so TalkBack reads
 *   "Español, selected, radio button"; the row is a `selectableGroup`
 *   so it announces as one choice.
 *
 * Picking applies the #1585 override immediately; the activity restarts
 * in the new language and the (saveable) onboarding step stays put, so
 * the user watches this same screen turn into Spanish and carries on.
 */
@Composable
internal fun OnboardingLanguagePicker(
    selected: AppLanguage,
    onPick: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.onboarding_language_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() },
        )
        Spacer(Modifier.height(spacing.sm))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            LanguageOption(
                label = stringResource(R.string.settings_appearance_language_english),
                isSelected = selected == AppLanguage.English,
                onClick = { onPick(AppLanguage.English) },
                modifier = Modifier.weight(1f),
            )
            LanguageOption(
                label = stringResource(R.string.settings_appearance_language_spanish),
                isSelected = selected == AppLanguage.Spanish,
                onClick = { onPick(AppLanguage.Spanish) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            stringResource(R.string.onboarding_language_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LanguageOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BrassButton(
        label = label,
        onClick = onClick,
        variant = if (isSelected) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
        selected = isSelected,
        modifier = modifier.heightIn(min = 64.dp),
    )
}

/**
 * The language the onboarding is showing right now, as one of the two
 * choices. An explicit override wins; "follow the device" resolves from
 * the device's language, so a phone already set to Spanish shows
 * Español as selected without the user touching anything.
 */
internal fun effectiveOnboardingLanguage(override: AppLanguage, uiLanguage: String): AppLanguage =
    when (override) {
        AppLanguage.English, AppLanguage.Spanish -> override
        AppLanguage.System -> if (isSpanish(uiLanguage)) AppLanguage.Spanish else AppLanguage.English
    }
