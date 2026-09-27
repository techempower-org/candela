package `in`.jphe.storyvox.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.repository.GoogleNewsEdition
import `in`.jphe.storyvox.data.repository.GoogleNewsFeedStore
import `in`.jphe.storyvox.data.repository.GoogleNewsPersonalFeed
import `in`.jphe.storyvox.data.repository.GoogleNewsTopic
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Issue #1678 — Settings → Content Sources → "Your Google News feed".
 *
 * Google exposes no API for the signed-in "For you" feed (Chrome new-tab /
 * Discover), so the user builds a personalized feed from explicit signals:
 * edition (language + region, incl. Spanish editions), followed topics,
 * places and saved searches. `:source-google-news` turns each into a Browse
 * section plus a merged "For you" section. State lives in
 * [GoogleNewsFeedStore] (owned by the source module) — deliberately not the
 * shared settings repository, so this feature adds no SettingsRepositoryUi
 * surface and no settings↔source Dagger edge.
 */
@HiltViewModel
class GoogleNewsFeedViewModel @Inject constructor(
    private val store: GoogleNewsFeedStore,
) : ViewModel() {

    /** Null until the store's first emission (renders a skeleton). */
    val feed: StateFlow<GoogleNewsPersonalFeed?> =
        store.feed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun edit(transform: (GoogleNewsPersonalFeed) -> GoogleNewsPersonalFeed) {
        viewModelScope.launch { store.update(transform) }
    }

    fun setEdition(edition: GoogleNewsEdition) = edit { it.copy(edition = edition) }
    fun setTopic(topic: GoogleNewsTopic, followed: Boolean) = edit { it.withTopic(topic, followed) }
    fun addPlace(place: String) = edit { it.addLocation(place) }
    fun removePlace(place: String) = edit { it.removeLocation(place) }
    fun addSearch(term: String) = edit { it.addSearch(term) }
    fun removeSearch(term: String) = edit { it.removeSearch(term) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoogleNewsFeedSettingsScreen(
    onBack: () -> Unit,
    viewModel: GoogleNewsFeedViewModel = hiltViewModel(),
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(
        title = stringResource(R.string.gnews_feed_title),
        onBack = onBack,
    ) { padding ->
        val f = feed ?: run {
            SettingsSkeleton(modifier = Modifier.padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            Text(
                text = stringResource(R.string.gnews_feed_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.xs),
            )

            // ─── Topics ──────────────────────────────────────────────────
            Column {
                SettingsSectionHeader(label = stringResource(R.string.gnews_feed_topics_header))
                SettingsGroupCard {
                    Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Hint(R.string.gnews_feed_topics_hint)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                        ) {
                            GoogleNewsTopic.entries.forEach { topic ->
                                val selected = topic in f.topics
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.setTopic(topic, !selected) },
                                    label = { Text(stringResource(topicLabel(topic))) },
                                    leadingIcon = if (selected) {
                                        {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ─── Places ──────────────────────────────────────────────────
            TermListSection(
                header = R.string.gnews_feed_places_header,
                hint = R.string.gnews_feed_places_hint,
                inputLabel = R.string.gnews_feed_place_label,
                terms = f.locations,
                onAdd = viewModel::addPlace,
                onRemove = viewModel::removePlace,
            )

            // ─── Searches ────────────────────────────────────────────────
            TermListSection(
                header = R.string.gnews_feed_searches_header,
                hint = R.string.gnews_feed_searches_hint,
                inputLabel = R.string.gnews_feed_search_label,
                terms = f.searches,
                onAdd = viewModel::addSearch,
                onRemove = viewModel::removeSearch,
            )

            // ─── Edition ─────────────────────────────────────────────────
            Column {
                SettingsSectionHeader(label = stringResource(R.string.gnews_feed_edition_header))
                SettingsGroupCard(modifier = Modifier.selectableGroup()) {
                    GoogleNewsEdition.entries.forEach { edition ->
                        val selected = edition == f.edition
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.setEdition(edition) },
                                )
                                .padding(horizontal = spacing.md, vertical = spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            // onClick = null: the whole row is the touch target
                            // and carries the radio semantics (one TalkBack stop).
                            RadioButton(selected = selected, onClick = null)
                            Text(
                                text = stringResource(editionLabel(edition)),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A followed-terms list (places / searches): add field + removable rows. */
@Composable
private fun TermListSection(
    @StringRes header: Int,
    @StringRes hint: Int,
    @StringRes inputLabel: Int,
    terms: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var draft by rememberSaveable { mutableStateOf("") }
    val full = terms.size >= GoogleNewsPersonalFeed.MAX_ENTRIES
    val canAdd = !full && GoogleNewsPersonalFeed.normalizeTerm(draft) != null
    val submit = {
        if (canAdd) {
            onAdd(draft)
            draft = ""
        }
    }

    Column {
        SettingsSectionHeader(label = stringResource(header))
        SettingsGroupCard {
            Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Hint(hint)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(GoogleNewsPersonalFeed.MAX_TERM_LENGTH) },
                        label = { Text(stringResource(inputLabel)) },
                        singleLine = true,
                        enabled = !full,
                        supportingText = if (full) {
                            { Text(stringResource(R.string.gnews_feed_limit_reached, GoogleNewsPersonalFeed.MAX_ENTRIES)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = submit,
                        enabled = canAdd,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.gnews_feed_add))
                    }
                }
            }
            if (terms.isEmpty()) {
                Text(
                    text = stringResource(R.string.gnews_feed_empty_list),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
                )
            }
            terms.forEach { term ->
                SettingsRow(
                    title = term,
                    trailing = {
                        IconButton(onClick = { onRemove(term) }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.gnews_feed_remove, term),
                            )
                        }
                    },
                )
            }
        }
    }
}

@StringRes
internal fun topicLabel(topic: GoogleNewsTopic): Int = when (topic) {
    GoogleNewsTopic.WORLD -> R.string.gnews_topic_world
    GoogleNewsTopic.NATION -> R.string.gnews_topic_nation
    GoogleNewsTopic.BUSINESS -> R.string.gnews_topic_business
    GoogleNewsTopic.TECHNOLOGY -> R.string.gnews_topic_technology
    GoogleNewsTopic.ENTERTAINMENT -> R.string.gnews_topic_entertainment
    GoogleNewsTopic.SPORTS -> R.string.gnews_topic_sports
    GoogleNewsTopic.SCIENCE -> R.string.gnews_topic_science
    GoogleNewsTopic.HEALTH -> R.string.gnews_topic_health
}

@StringRes
internal fun editionLabel(edition: GoogleNewsEdition): Int = when (edition) {
    GoogleNewsEdition.US_EN -> R.string.gnews_edition_us_en
    GoogleNewsEdition.US_ES -> R.string.gnews_edition_us_es
    GoogleNewsEdition.MX_ES -> R.string.gnews_edition_mx_es
    GoogleNewsEdition.ES_ES -> R.string.gnews_edition_es_es
    GoogleNewsEdition.AR_ES -> R.string.gnews_edition_ar_es
    GoogleNewsEdition.CO_ES -> R.string.gnews_edition_co_es
    GoogleNewsEdition.GB_EN -> R.string.gnews_edition_gb_en
    GoogleNewsEdition.CA_EN -> R.string.gnews_edition_ca_en
    GoogleNewsEdition.IN_EN -> R.string.gnews_edition_in_en
    GoogleNewsEdition.AU_EN -> R.string.gnews_edition_au_en
}
