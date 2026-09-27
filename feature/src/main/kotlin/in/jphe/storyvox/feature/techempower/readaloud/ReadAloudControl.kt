package `in`.jphe.storyvox.feature.techempower.readaloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.a11y.LocalAccessibleTouchTargets
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1580 — the one read-aloud affordance shared by every TechEMPOWER
 * benefits surface (epic #1520 invariant 4b).
 *
 * A single, full-width toggle: "Read aloud" (speaker icon) ⇄ "Stop reading"
 * (stop icon). Big on purpose — this audience skews ESL, low-literacy and
 * low-vision, so the control floors at 56dp tall (64dp under the app's
 * "Larger touch targets" setting, matching BrassButton #690) with a 24dp icon
 * and a visible text label; TalkBack reads the label plus a "Reading aloud"
 * state while speech is in flight.
 *
 * [script] is evaluated at tap time, so a surface can pass a lambda that
 * reads its latest state (e.g. text the user just typed). Build it from
 * localized strings with [ReadAloudScript] so the spoken language follows the
 * app language.
 *
 * @param key identifies this control among others on the same screen — only
 *   the control that started the utterance flips to "Stop".
 * @param label the idle-state label; defaults to "Read aloud".
 */
@Composable
fun ReadAloudControl(
    key: String,
    script: () -> String,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.readaloud_action),
    viewModel: ReadAloudViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReadAloudButton(
        active = state.isActive(key),
        failed = state.failedKey == key,
        label = label,
        onClick = { viewModel.toggle(key, script()) },
        modifier = modifier,
    )
}

/** Stateless visual for [ReadAloudControl]. */
@Composable
fun ReadAloudButton(
    active: Boolean,
    failed: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val minHeight = if (LocalAccessibleTouchTargets.current) 64.dp else 56.dp
    val speakingState = stringResource(R.string.readaloud_state_speaking)
    val buttonModifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = minHeight)
        .semantics { if (active) stateDescription = speakingState }
    val padding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        if (active) {
            FilledTonalButton(onClick = onClick, modifier = buttonModifier, contentPadding = padding) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(spacing.sm))
                Text(stringResource(R.string.readaloud_stop), style = MaterialTheme.typography.titleSmall)
            }
        } else {
            OutlinedButton(
                onClick = onClick,
                modifier = buttonModifier,
                contentPadding = padding,
            ) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(spacing.sm))
                Text(label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            }
        }
        if (failed && !active) {
            Text(
                stringResource(R.string.readaloud_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
