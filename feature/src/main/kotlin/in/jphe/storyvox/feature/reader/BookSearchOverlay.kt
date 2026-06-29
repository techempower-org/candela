package `in`.jphe.storyvox.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.MagicCircularProgress
import `in`.jphe.storyvox.ui.component.TestTags
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/** Cap on the results panel's height so it never swallows the whole screen —
 *  the scrim below it stays tappable to dismiss. The list scrolls within. */
private val RESULTS_MAX_HEIGHT = 440.dp

/**
 * Issue #1229 — the reader's whole-book ("find in book") search overlay.
 *
 * A top-anchored brass panel: a query field, a stepper over the matching
 * chapters, and a results list (chapter title + match-count badge + a snippet
 * with the query term highlighted). Tapping a result — or pressing the IME
 * "Search" action on the selected one — jumps to that chapter at the match
 * (handled by [ReaderViewModel.openBookSearchResult]). The prev/next chevrons
 * cycle the selection through the results without leaving the overlay.
 *
 * Purely presentational: every piece of state ([BookSearchUiState]) and every
 * verb is hoisted to [ReaderViewModel], so the overlay can float over either
 * reader pane from [HybridReaderScreen]. Rendered unconditionally and gated by
 * [BookSearchUiState.open] so the slide/fade entrance and exit both animate.
 */
@Composable
fun BookSearchOverlay(
    state: BookSearchUiState,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onResultClick: (BookSearchResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // System back closes the overlay rather than popping the reader.
    BackHandler(enabled = state.open) { onClose() }

    AnimatedVisibility(
        visible = state.open,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize().testTag(TestTags.BookSearchOverlay)) {
            // Scrim: dims the reader and catches taps below the panel to dismiss.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.reader_search_close),
                        onClick = onClose,
                    ),
            )

            AnimatedVisibility(
                visible = state.open,
                enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(180)) { -it } + fadeOut(tween(180)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                BookSearchPanel(
                    state = state,
                    onQueryChange = onQueryChange,
                    onPrev = onPrev,
                    onNext = onNext,
                    onResultClick = onResultClick,
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun BookSearchPanel(
    state: BookSearchUiState,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onResultClick: (BookSearchResult) -> Unit,
    onClose: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val hasResults = state.results.isNotEmpty()

    // Focus the field as the overlay opens so the keyboard is ready to type.
    LaunchedEffect(state.open) {
        if (state.open) focus.requestFocus()
    }
    LaunchedEffect(state.results.size) {
        if (hasResults) listState.scrollToItem(0)
    }
    LaunchedEffect(state.selectedIndex) {
        if (hasResults && state.selectedIndex in state.results.indices) {
            listState.animateScrollToItem(state.selectedIndex)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.sm)) {
            // ── Query field + close ──────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.reader_book_search_field_label)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        // Enter on the selected result jumps to it ("search & go").
                        onSearch = { state.results.getOrNull(state.selectedIndex)?.let(onResultClick) },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .testTag(TestTags.BookSearchField),
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag(TestTags.BookSearchClose),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.reader_search_close),
                    )
                }
            }

            // ── Stepper row (only when there are results) ────────────────
            if (hasResults) {
                val positionDescription = stringResource(
                    R.string.reader_book_search_position_description,
                    state.selectedIndex + 1,
                    state.results.size,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.reader_book_search_chapter_count,
                            state.results.size,
                            state.results.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.reader_book_search_position,
                            state.selectedIndex + 1,
                            state.results.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = spacing.xs)
                            .semantics { contentDescription = positionDescription },
                    )
                    IconButton(onClick = onPrev) {
                        Icon(
                            Icons.Outlined.ExpandLess,
                            contentDescription = stringResource(R.string.reader_book_search_prev),
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(R.string.reader_book_search_next),
                        )
                    }
                }
            }

            // ── Results / status ─────────────────────────────────────────
            when {
                hasResults -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = RESULTS_MAX_HEIGHT)
                            .testTag(TestTags.BookSearchResults),
                    ) {
                        itemsIndexed(
                            items = state.results,
                            key = { _, r -> r.chapterId },
                        ) { index, result ->
                            BookSearchResultRow(
                                result = result,
                                selected = index == state.selectedIndex,
                                onClick = { onResultClick(result) },
                            )
                        }
                        if (state.truncated) {
                            item(key = "truncated-note") {
                                Text(
                                    text = stringResource(
                                        R.string.reader_book_search_truncated,
                                        state.results.size,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(spacing.md),
                                )
                            }
                        }
                    }
                }
                state.phase == BookSearchPhase.Searching -> {
                    StatusRow {
                        MagicCircularProgress(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(spacing.sm))
                        Text(
                            stringResource(R.string.reader_book_search_searching),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.phase == BookSearchPhase.Done -> {
                    StatusRow {
                        Text(
                            stringResource(R.string.reader_book_search_no_matches),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    StatusRow {
                        Text(
                            stringResource(R.string.reader_book_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(content: @Composable () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun BookSearchResultRow(
    result: BookSearchResult,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val highlightBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    val snippet = remember(result.snippet, result.snippetMatchRange, highlightBg) {
        buildAnnotatedString {
            append(result.snippet)
            val range = result.snippetMatchRange
            val start = range.first.coerceIn(0, result.snippet.length)
            val end = (range.last + 1).coerceIn(start, result.snippet.length)
            if (end > start) {
                addStyle(
                    SpanStyle(background = highlightBg, fontWeight = FontWeight.SemiBold),
                    start,
                    end,
                )
            }
        }
    }
    val rowColor =
        if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.reader_book_search_go_to),
                onClick = onClick,
            )
            .padding(horizontal = spacing.md, vertical = spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.chapterTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = pluralStringResource(
                    R.plurals.reader_book_search_match_badge,
                    result.matchCount,
                    result.matchCount,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(spacing.xs))
        Text(
            text = snippet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
