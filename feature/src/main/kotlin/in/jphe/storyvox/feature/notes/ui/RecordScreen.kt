package `in`.jphe.storyvox.feature.notes.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Voice Notes (epic #1657, Phase 4) — the recording screen. A large timer, an
 * animated waveform, and record / pause / resume / stop transport. Stop persists
 * the recording as a note and opens its detail.
 *
 * **Capture is stubbed in Phase 4** (see [NotesRecordViewModel]) — the chrome is
 * final; Phase 2a wires the real `AudioRecorder` + microphone FGS in behind it.
 *
 * @param onRecordingSaved open the detail of the just-saved note by id.
 * @param onExit leave the screen (cancel / back) without saving.
 */
@Composable
fun RecordScreen(
    onRecordingSaved: (String) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotesRecordViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RecordEvent.Saved -> onRecordingSaved(event.noteId)
            }
        }
    }

    // RECORD_AUDIO is requested the moment the user first taps record: granted →
    // start immediately; denied → no-op (the button stays available to retry).
    // Capture + the microphone FGS both require it.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.start() }
    val onStartRecording: () -> Unit = {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.start()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val cancelAndExit = {
        viewModel.cancel()
        onExit()
    }
    // System back mirrors the top-bar close: abandon the in-progress take.
    BackHandler(enabled = true, onBack = cancelAndExit)

    RecordContent(
        state = state,
        onStart = onStartRecording,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onStop = viewModel::stopAndSave,
        onCancel = cancelAndExit,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordContent(
    state: RecordUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Record") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = formatNoteDuration(state.elapsedMs),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = when {
                    state.isPaused -> "Paused"
                    state.isRecording -> "Recording…"
                    else -> "Tap to record"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(spacing.xl))

            Waveform(
                amplitudes = state.amplitudes,
                active = state.isRecording && !state.isPaused,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )

            Spacer(Modifier.height(spacing.xl))

            if (!state.isRecording) {
                // Idle → one big primary button to begin.
                FilledIconButton(
                    onClick = onStart,
                    modifier = Modifier.size(88.dp),
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Start recording",
                        modifier = Modifier.size(40.dp),
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Pause / Resume toggle.
                    FilledTonalIconButton(
                        onClick = if (state.isPaused) onResume else onPause,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            imageVector = if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (state.isPaused) "Resume" else "Pause",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    // Stop + save.
                    FilledIconButton(
                        onClick = onStop,
                        enabled = !state.isSaving,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop and save",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A centered bar waveform over recent [amplitudes] (each in `[0f, 1f]`). When
 * there's nothing to draw yet it shows a faint baseline so the area reads as a
 * waveform surface rather than empty space.
 */
@Composable
private fun Waveform(
    amplitudes: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Canvas(modifier = modifier) {
        val bars = 48
        val barSlot = size.width / bars
        val barWidth = barSlot * 0.5f
        val midY = size.height / 2f

        if (amplitudes.isEmpty()) {
            drawRoundRect(
                color = idleColor,
                topLeft = Offset(0f, midY - 1.5f),
                size = Size(size.width, 3f),
                cornerRadius = CornerRadius(1.5f, 1.5f),
            )
            return@Canvas
        }

        val recent = amplitudes.takeLast(bars)
        // Right-align newest bars so the trace grows leftward like a real meter.
        val startIndex = bars - recent.size
        val color = if (active) barColor else idleColor
        recent.forEachIndexed { i, amp ->
            val barHeight = (size.height * 0.92f) * amp.coerceIn(0f, 1f)
            val x = (startIndex + i) * barSlot + (barSlot - barWidth) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, midY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────

private fun sampleAmplitudes(): List<Float> =
    (1..48).map { simulatedAmplitude(it) }

@Preview(name = "Record · active", showBackground = true)
@Preview(name = "Record · active dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun RecordActivePreview() {
    LibraryNocturneTheme {
        RecordContent(
            state = RecordUiState(
                elapsedMs = 73_000,
                isRecording = true,
                isPaused = false,
                amplitudes = sampleAmplitudes(),
            ),
            onStart = {},
            onPause = {},
            onResume = {},
            onStop = {},
            onCancel = {},
        )
    }
}

@Preview(name = "Record · idle", showBackground = true)
@Composable
private fun RecordIdlePreview() {
    LibraryNocturneTheme {
        RecordContent(
            state = RecordUiState(),
            onStart = {},
            onPause = {},
            onResume = {},
            onStop = {},
            onCancel = {},
        )
    }
}
