package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → About → Open-source licenses (issue #1142).
 *
 * Before this screen the About hub advertised "open-source notices" but no
 * surface rendered any — and Candela ships under **GPL-3.0**, so conveying
 * the project's own license + an offer of source, plus the licenses of every
 * bundled dependency, is a license obligation, not a nicety.
 *
 * Two parts:
 *  1. A brass header card stating Candela's own GPL-3.0 license and linking to
 *     the public source repository (the GPL "offer source" obligation).
 *  2. [LibrariesContainer] from mikepenz/AboutLibraries (GMS-free), rendering
 *     the full dependency + license list. The data is produced up in `:app`
 *     via `produceLibraries(R.raw.aboutlibraries)` — the AboutLibraries
 *     `.android` Gradle plugin generates that resource against `:app`'s
 *     resolved graph, so it covers every `:core-*`, `:source-*`, and
 *     transitive dependency (the whole point: completeness). `:app` hands the
 *     resulting [Libs] in here so this screen can live with its Settings
 *     subscreen siblings and reuse [SettingsSubscreenScaffold].
 *
 * [libraries] is null only for the brief frame before the raw resource is
 * parsed; [LibrariesContainer] renders its own loading state, and the GPL
 * header is always present regardless.
 */
@Composable
fun OssLicensesScreen(
    libraries: Libs?,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val uriHandler = LocalUriHandler.current
    val sourceUrl = stringResource(R.string.settings_licenses_source_url)
    val sourceLabel = stringResource(R.string.settings_licenses_source)

    SettingsSubscreenScaffold(
        title = stringResource(R.string.settings_licenses_title),
        onBack = onBack,
    ) { padding ->
        // NB: not SettingsSubscreenBody — that wraps content in a
        // verticalScroll, which would nest-scroll-fight LibrariesContainer's
        // own LazyColumn. We scaffold directly and let the list own scrolling.
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SettingsGroupCard(
                modifier = Modifier.padding(
                    horizontal = spacing.md,
                    vertical = spacing.md,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = spacing.md,
                        vertical = spacing.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.settings_licenses_gpl_heading),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.settings_licenses_gpl_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = spacing.xxs)
                            .clickable { runCatching { uriHandler.openUri(sourceUrl) } }
                            .semantics { contentDescription = sourceLabel },
                    )
                }
            }
            LibrariesContainer(
                libraries = libraries,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}
