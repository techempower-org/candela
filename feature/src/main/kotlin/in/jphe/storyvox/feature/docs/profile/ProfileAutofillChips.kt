package `in`.jphe.storyvox.feature.docs.profile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R

/**
 * Issue #1519 — the reusable autofill affordance for one detected form
 * field. This is the drop-in the photo→fillable-PDF screen (#1512, not yet
 * merged) places next to each detected field: pass the field's OCR'd
 * label, the saved [HouseholdProfile], and an `onFill` callback.
 *
 * Behaviour:
 *  - Sensitive (SSN / tax-id) label → a **warning**, never a suggestion
 *    (issue policy: SSN is type-to-fill only, never persisted).
 *  - A known field with a saved value → a one-tap "use saved …" chip.
 *  - Otherwise → nothing (no chip, no clutter).
 *
 * Pure UI over [ProfileAutofillMatcher]; no VM needed, so #1512 can use it
 * with just the profile it already holds. TODO(#1512): wire into the
 * detected-field rows once the fillable-form screen lands.
 */
@Composable
fun ProfileAutofillChip(
    detectedLabel: String,
    profile: HouseholdProfile,
    onFill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ProfileAutofillMatcher.isSensitive(detectedLabel)) {
        SensitiveFieldNote(modifier)
        return
    }
    val field = ProfileAutofillMatcher.match(detectedLabel) ?: return
    val value = profile.valueFor(field)
    if (value.isBlank()) return

    val cd = stringResource(R.string.profile_use_saved_cd, value)
    AssistChip(
        onClick = { onFill(value) },
        label = { Text(stringResource(R.string.profile_use_saved, value)) },
        modifier = modifier.semantics { contentDescription = cd },
    )
}

/** Warning shown in place of a suggestion for an SSN / tax-id field. */
@Composable
fun SensitiveFieldNote(modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.profile_ssn_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
