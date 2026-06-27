package `in`.jphe.storyvox.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R

/**
 * Issue #793 — Library "All" shelf sort affordance. Renders as a small
 * brass-tinted [AssistChip] showing the leading [Icons.AutoMirrored.Outlined.Sort]
 * glyph + the active mode's label; tapping opens a [DropdownMenu] of
 * the five [LibrarySortMode] options. Visual rhythm mirrors the
 * existing [ShelfChipRow] FilterChips (rounded, primaryContainer-tinted)
 * so the two chip surfaces read as members of the same row family.
 *
 * Placed inline at the end of the ShelfChipRow rather than in a
 * separate row — a second strip just for sort would push the Library
 * grid down another 48dp and the chip count (4 + 1) still fits on the
 * Flip3 cover when the row uses horizontalScroll.
 */
@Composable
fun LibrarySortChip(
    selected: LibrarySortMode,
    onSelect: (LibrarySortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val chipCd = stringResource(R.string.library_sort_chip_cd)
    // #1157 — the static contentDescription ("Sort library") used to
    // *override* the visible active-mode label, so TalkBack never spoke
    // the current sort. Publish the active mode as the node's state so
    // it announces "Sort library, Recently added" instead of just
    // "Sort library, button".
    val currentSortLabel = labelFor(selected)
    Box(modifier = modifier) {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(labelFor(selected)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Sort,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f),
                labelColor = MaterialTheme.colorScheme.onSurface,
                leadingIconContentColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.semantics {
                role = Role.Button
                contentDescription = chipCd
                stateDescription = currentSortLabel
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LibrarySortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(labelFor(mode)) },
                    onClick = {
                        expanded = false
                        if (mode != selected) onSelect(mode)
                    },
                    leadingIcon = if (mode == selected) {
                        {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun labelFor(mode: LibrarySortMode): String = when (mode) {
    LibrarySortMode.Title -> stringResource(R.string.library_sort_title)
    LibrarySortMode.Author -> stringResource(R.string.library_sort_author)
    LibrarySortMode.RecentlyAdded -> stringResource(R.string.library_sort_recently_added)
    LibrarySortMode.RecentlyPlayed -> stringResource(R.string.library_sort_recently_played)
    LibrarySortMode.LongestUnread -> stringResource(R.string.library_sort_longest_unread)
}
