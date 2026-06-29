package `in`.jphe.storyvox.feature.browse.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.browse.BrowseSourceUi
import `in`.jphe.storyvox.feature.browse.sourceGlyph
import `in`.jphe.storyvox.ui.component.TestTags
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Source Catalog (#1365) — per-category theme accent.
 *
 * Each catalog section reads with its own colour so the four shelves are
 * visually distinct without leaving the brass palette. Pulled from the
 * Material3 [androidx.compose.material3.ColorScheme] roles (not literal
 * hex) so dark/light both resolve automatically:
 *  - Books  → primary    (brass — the app's signature)
 *  - Text   → tertiary
 *  - Audio  → secondary
 *  - Other  → onSurfaceVariant (neutral)
 */
@Composable
internal fun catalogAccent(group: CatalogGroup): Color = when (group) {
    CatalogGroup.Books -> MaterialTheme.colorScheme.primary
    CatalogGroup.Text -> MaterialTheme.colorScheme.tertiary
    CatalogGroup.Audio -> MaterialTheme.colorScheme.secondary
    CatalogGroup.Other -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Source Catalog (#1365) — section header: a coloured accent bar, the
 * group title, and the source count. The accent bar ties the header to
 * its cards' icon tint so the eye reads "this shelf" at a glance.
 */
@Composable
internal fun SourceCatalogSectionHeader(
    group: CatalogGroup,
    count: Int,
) {
    val spacing = LocalSpacing.current
    val accent = catalogAccent(group)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.sm, bottom = spacing.xs)
            // One spoken node: "Books & Literature, 8 sources".
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Text(
            text = group.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Text(
            text = if (count == 1) "1 source" else "$count sources",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Source Catalog (#1365) — a rich, shelf-style card for one source.
 *
 * Layout: an accent-tinted icon tile on the left (the same id→glyph the
 * Browse carousel uses, via [sourceGlyph]), the source name + one-line
 * description in the middle with a row of small badges (category +
 * capabilities), and a brass enable switch on the right.
 *
 * Two affordances, deliberately separated:
 *  - **Tap the card body** → browse this source ([onOpen]). The card is
 *    the invitation; tapping it is "take me there".
 *  - **The switch** → enable/disable in place ([onToggle]) without
 *    leaving the catalog, for users curating which sources show up in
 *    Browse. The switch swallows its own taps so toggling never also
 *    fires the browse navigation.
 *
 * Disabled sources render at reduced emphasis (the card reads as "off")
 * but stay tappable — tapping a disabled source enables it on the way to
 * Browse (see [SourceCatalogViewModel.onSourceChosen]).
 */
@Composable
internal fun SourceCatalogCard(
    row: SourceCatalogRow,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val descriptor = row.descriptor
    val group = catalogGroupOf(descriptor.category)
    val accent = catalogAccent(group)
    val brass = MaterialTheme.colorScheme.primary

    val label = BrowseSourceUi.chipLabel(descriptor.id, descriptor.displayName)
    // Disabled cards hold back the title/icon emphasis so the shelf reads
    // enabled-vs-not at a glance without hiding the source entirely.
    val contentAlpha = if (row.enabled) 1f else 0.55f

    val brassColors = SwitchDefaults.colors(
        checkedThumbColor = brass,
        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
        checkedBorderColor = brass,
        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.sourceCatalogCard(descriptor.id))
            // a11y: the whole card is the "browse this source" button.
            // The switch below overrides with its own toggle semantics.
            .clickable(
                role = Role.Button,
                onClickLabel = "Browse $label",
                onClick = onOpen,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (row.enabled) accent.copy(alpha = 0.40f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Accent-tinted icon tile — the catalog's "book spine".
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = if (row.enabled) 0.16f else 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = sourceGlyph(descriptor.id),
                    contentDescription = null,
                    tint = accent.copy(alpha = contentAlpha),
                    modifier = Modifier.size(26.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = descriptor.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (descriptor.description.isNotBlank()) {
                    Text(
                        text = descriptor.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.size(spacing.xs))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CatalogBadge(text = group.badge, accent = accent, filled = true)
                    if (descriptor.supportsSearch) {
                        CatalogBadge(text = "Search", accent = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (descriptor.supportsFollow) {
                        CatalogBadge(text = "Follow", accent = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Enable/disable in place. contentDescription so TalkBack reads
            // "Enable <source>, switch, on" instead of a bare "switch".
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                colors = brassColors,
                modifier = Modifier.semantics {
                    contentDescription = "Enable ${descriptor.displayName}"
                },
            )
        }
    }
}

/**
 * Small pill used for the category + capability badges on a catalog
 * card. `filled` gives the category badge a tinted background so it
 * stands out from the outlined capability badges next to it.
 */
@Composable
private fun CatalogBadge(
    text: String,
    accent: Color,
    filled: Boolean = false,
) {
    val spacing = LocalSpacing.current
    Surface(
        shape = RoundedCornerShape(50),
        color = if (filled) accent.copy(alpha = 0.16f) else Color.Transparent,
        border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.xs, vertical = 2.dp),
        )
    }
}
