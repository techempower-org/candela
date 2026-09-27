package `in`.jphe.storyvox.feature.techempower.learnpaths

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1464 — guided digital-literacy learning paths.
 *
 * List view: a "next step" hero (the recommended path's first unfinished
 * lesson, one tap to listen) above a card per path with a progress bar. Path
 * view: the ordered lessons, each marked done / next / not started; tapping a
 * lesson narrates it. Paths come from the bundled JSON — this screen has no
 * per-path code. Chrome is bilingual via `strings_learnpaths.xml`; path and
 * lesson titles carry their own EN/ES and follow the locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningPathsScreen(
    onBack: () -> Unit,
    onOpenReader: (fictionId: String, chapterId: String) -> Unit,
    viewModel: LearningPathsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val spanish = LocalConfiguration.current.locales[0].language == "es"
    val selected = state.selectedPath

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LearningPathsEvent.OpenReader -> onOpenReader(event.fictionId, event.chapterId)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        selected?.title?.get(spanish) ?: stringResource(R.string.learnpaths_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) viewModel.backToList() else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.learnpaths_back),
                        )
                    }
                },
            )
        },
    ) { pad ->
        val corpus = state.corpus
        when {
            state.isLoading -> Centered(Modifier.padding(pad)) { CircularProgressIndicator() }
            state.loadError || corpus == null -> Centered(Modifier.padding(pad)) {
                Text(
                    stringResource(R.string.learnpaths_load_error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            selected != null -> PathDetail(
                path = selected,
                progress = state.progressOf(selected.id),
                spanish = spanish,
                onStartStep = viewModel::startStep,
                modifier = Modifier.padding(pad),
            )
            else -> PathList(
                state = state,
                corpus = corpus,
                spanish = spanish,
                onSelect = viewModel::select,
                onStartStep = viewModel::startStep,
                modifier = Modifier.padding(pad),
            )
        }
    }
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun PathList(
    state: LearningPathsUiState,
    corpus: LearningPathsCorpus,
    spanish: Boolean,
    onSelect: (String) -> Unit,
    onStartStep: (PathStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val recommended = state.recommended
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Text(
                stringResource(R.string.learnpaths_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            val path = recommended?.let { state.pathById(it.pathId) }
            val step = recommended?.nextStepIndex?.let { path?.steps?.getOrNull(it) }
            if (recommended != null && path != null && step != null) {
                NextStepHero(
                    heading = stringResource(
                        if (recommended.isInProgress) R.string.learnpaths_continue_heading
                        else R.string.learnpaths_start_heading,
                    ),
                    path = path,
                    step = step,
                    progress = recommended,
                    spanish = spanish,
                    onListen = { onStartStep(step) },
                )
            } else if (corpus.paths.isNotEmpty()) {
                AllCompleteBanner()
            }
        }
        items(corpus.paths, key = { it.id }) { path ->
            PathCard(
                path = path,
                progress = state.progressOf(path.id),
                spanish = spanish,
                onClick = { onSelect(path.id) },
            )
        }
    }
}

@Composable
private fun NextStepHero(
    heading: String,
    path: LearningPath,
    step: PathStep,
    progress: PathProgress,
    spanish: Boolean,
    onListen: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.5.dp, brass, MaterialTheme.shapes.large)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            heading,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            path.title.get(spanish),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
        ProgressLine(progress)
        step.why?.let {
            Text(
                it.get(spanish),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Button(
            onClick = onListen,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text(
                stringResource(R.string.learnpaths_listen_step, step.title.get(spanish)),
                modifier = Modifier.padding(start = spacing.xs),
            )
        }
    }
}

@Composable
private fun AllCompleteBanner() {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            stringResource(R.string.learnpaths_all_complete),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun ProgressLine(progress: PathProgress?) {
    progress ?: return
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        // The bar is decorative — the text beside it carries the value for
        // TalkBack, so the pair isn't announced twice.
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { },
        )
        Text(
            if (progress.isComplete) stringResource(R.string.learnpaths_complete)
            else stringResource(R.string.learnpaths_progress, progress.completed, progress.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PathCard(
    path: LearningPath,
    progress: PathProgress?,
    spanish: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.5.dp, brass.copy(alpha = 0.55f), MaterialTheme.shapes.large)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            if (progress?.isComplete == true) Icons.Filled.CheckCircle else Icons.Filled.School,
            contentDescription = null,
            tint = brass,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                path.title.get(spanish),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            path.summary?.let {
                Text(
                    it.get(spanish),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProgressLine(progress)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = brass)
    }
}

@Composable
private fun PathDetail(
    path: LearningPath,
    progress: PathProgress?,
    spanish: Boolean,
    onStartStep: (PathStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val nextIndex = progress?.nextStepIndex
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                path.summary?.let {
                    Text(it.get(spanish), style = MaterialTheme.typography.bodyMedium)
                }
                ProgressLine(progress)
            }
        }
        item {
            Text(
                stringResource(R.string.learnpaths_steps_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
        }
        itemsIndexed(path.steps, key = { i, s -> "$i:${s.pageId}" }) { index, step ->
            StepRow(
                number = index + 1,
                total = path.steps.size,
                step = step,
                done = progress?.stepDone?.getOrNull(index) == true,
                isNext = index == nextIndex,
                spanish = spanish,
                onClick = { onStartStep(step) },
            )
        }
    }
}

@Composable
private fun StepRow(
    number: Int,
    total: Int,
    step: PathStep,
    done: Boolean,
    isNext: Boolean,
    spanish: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val title = step.title.get(spanish)
    val cd = stringResource(
        when {
            done -> R.string.learnpaths_step_done_cd
            isNext -> R.string.learnpaths_step_next_cd
            else -> R.string.learnpaths_step_todo_cd
        },
        number,
        total,
        title,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (isNext) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .then(
                if (isNext) Modifier.border(1.5.dp, brass, MaterialTheme.shapes.medium) else Modifier,
            )
            .clickable(onClick = onClick, role = Role.Button)
            // One spoken summary ("Lesson 2 of 3, Free internet, next up");
            // the visible texts below are cleared so they aren't read twice.
            .semantics { contentDescription = cd }
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) brass else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "$number. $title",
                style = MaterialTheme.typography.titleSmall,
                color = if (isNext) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            step.why?.let {
                Text(
                    it.get(spanish),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isNext) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isNext) {
                Text(
                    stringResource(R.string.learnpaths_next_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            } else if (done) {
                Text(
                    stringResource(R.string.learnpaths_listen_again),
                    style = MaterialTheme.typography.labelMedium,
                    color = brass,
                )
            }
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = brass)
    }
}
