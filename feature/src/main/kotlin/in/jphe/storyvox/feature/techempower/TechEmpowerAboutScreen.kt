package `in`.jphe.storyvox.feature.techempower

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.TechEmpowerLinks
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import `in`.jphe.storyvox.ui.R as UiR

/**
 * Issue #517 — "About TechEMPOWER" sub-screen reached from the
 * TechEmpower Home grid. Mission statement, donate flow, partnerships
 * contact, and 501(c)(3) attribution.
 *
 * The Donate affordance link-outs to `techempower.org/donate` via the
 * system browser rather than embedding Stripe in-app — the 501(c)(3)
 * board conversation about payment-card handling and the compliance
 * scope it implies is bigger than this PR (see
 * blocking-questions.md suggestion 3). Link-out is the right v1
 * answer regardless: storyvox is an audiobook app, and money paths
 * belong with the org's primary website.
 *
 * Library Nocturne palette throughout — no warm-earth-tones on
 * storyvox surfaces (JP design call #3).
 *
 * v0.5.51 — first pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechEmpowerAboutScreen(
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "About TechEMPOWER",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to TechEMPOWER",
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Logo + tagline header.
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.md, bottom = spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Image(
                        painter = painterResource(id = UiR.drawable.techempower_sun),
                        contentDescription = "TechEMPOWER sun-disk logo",
                        modifier = Modifier
                            .size(120.dp)
                            .clip(MaterialTheme.shapes.large),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        text = TechEmpowerLinks.MISSION_TAGLINE,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Mission statement body — set in EB Garamond if the theme
            // exposes it; bodyLarge is the closest standard slot.
            item {
                Text(
                    text = TechEmpowerLinks.MISSION_STATEMENT,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Donate button — primary brass affordance. Sits ABOVE the
            // contact rows because it's the lead conversion path.
            item {
                BrassButton(
                    label = "Donate to TechEMPOWER",
                    variant = BrassButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(TechEmpowerLinks.DONATE_URL),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                            )
                        }
                    },
                )
            }

            // Section header — links + contact.
            item {
                Text(
                    text = "Get in touch",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }

            // Visit website link-row.
            item {
                AboutLinkRow(
                    icon = Icons.Filled.Language,
                    title = "Visit techempower.org",
                    subtitle = TechEmpowerLinks.WEBSITE_URL,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(TechEmpowerLinks.WEBSITE_URL),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                            )
                        }
                    },
                )
            }

            // Partnerships email row — surfaces a separate mailbox
            // from the user-facing contact path so partnership leads
            // route to the right inbox.
            item {
                AboutLinkRow(
                    icon = Icons.Filled.Email,
                    title = "Partnerships",
                    subtitle = TechEmpowerLinks.PARTNERSHIPS_EMAIL,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_SENDTO,
                                    Uri.parse(
                                        TechEmpowerLinks.mailtoUri(
                                            TechEmpowerLinks.PARTNERSHIPS_EMAIL,
                                        ),
                                    ),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                            )
                        }
                    },
                )
            }

            // Donate again, but as a secondary row form so the user can
            // re-reach the destination from a quieter affordance after
            // scrolling past the primary brass button. Pattern matches
            // Settings → About's "Visit GitHub" + "Report an issue"
            // double-surfacing for the same destination.
            item {
                AboutLinkRow(
                    icon = Icons.Filled.Favorite,
                    title = "Donate",
                    subtitle = TechEmpowerLinks.DONATE_URL,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(TechEmpowerLinks.DONATE_URL),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                            )
                        }
                    },
                )
            }

            // 501(c)(3) attribution footer. Quiet body text — required
            // by US nonprofit communication norms, set at bodySmall
            // with onSurfaceVariant so it reads as legal-footer-tier.
            item {
                Spacer(Modifier.height(spacing.md))
                Text(
                    text = "TechEMPOWER is a registered 501(c)(3) " +
                        "nonprofit organization. Contributions are " +
                        "tax-deductible to the extent allowed by law. " +
                        "storyvox is an open-source project built on " +
                        "TechEMPOWER's mission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Single brass-edged link row. Icon on the left, two-line label on
 * the right (title + URL/email subtitle). Tap fires [onClick].
 */
@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = brass.copy(alpha = 0.35f),
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = brass,
                modifier = Modifier.size(28.dp),
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = brass,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
