package `in`.jphe.storyvox.feature.reader.recording

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1367 — Recording mode.
 *
 * A full-screen surface that composites the (front, by default) camera preview
 * behind the semi-transparent teleprompter script — the chapter currently
 * loaded in the reader — so the user can film a vertical YouTube Short / Reel /
 * TikTok while reading. The script auto-scrolls at the reader's teleprompter
 * pace (#1308 WPM), there's a 3-2-1 countdown, a live red recording indicator,
 * an opacity slider, and a front/back flip; the finished MP4 saves straight to
 * the gallery (MediaStore → Movies/Candela).
 *
 * Reached from the Record button in the reader's teleprompter transport
 * ([StoryvoxRoutes.RECORDING]). State lives in [RecordingViewModel]; this
 * composable owns the lifecycle-bound [CameraRecorder] and executes the
 * ViewModel's [RecordingCommand]s.
 */
@Composable
fun RecordingScreen(
    onBack: () -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    // CAMERA + RECORD_AUDIO are both runtime-dangerous. CAMERA may already be
    // granted (the OCR scan flow asks for it), but audio recording needs the
    // mic too, so request the pair together. A denial leaves us on the
    // rationale card rather than dead-ending.
    val requiredPermissions = remember {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
    var granted by remember {
        mutableStateOf(requiredPermissions.all { hasPermission(context, it) })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it } &&
            requiredPermissions.all { hasPermission(context, it) }
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(requiredPermissions)
    }

    if (!granted) {
        PermissionRationale(
            onGrant = { permissionLauncher.launch(requiredPermissions) },
            onBack = onBack,
        )
        return
    }

    RecordingContent(viewModel = viewModel, onBack = onBack)
}

@Composable
private fun RecordingContent(
    viewModel: RecordingViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val script by viewModel.script.collectAsStateWithLifecycle()
    val wpm by viewModel.wpm.collectAsStateWithLifecycle()
    val opacity by viewModel.opacity.collectAsStateWithLifecycle()
    val frontCamera by viewModel.frontCamera.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val mirror by viewModel.mirror.collectAsStateWithLifecycle()

    // The camera lives with the composition's lifecycle — created here, not in
    // the ViewModel, so it's torn down when we leave the screen.
    val recorder = remember { CameraRecorder(context) }
    DisposableEffect(lifecycleOwner) {
        recorder.bind(lifecycleOwner)
        onDispose { recorder.release() }
    }
    LaunchedEffect(frontCamera) { recorder.setFrontCamera(frontCamera) }

    // Execute the ViewModel's camera commands against the bound recorder and
    // report results back into the state machine.
    LaunchedEffect(Unit) {
        viewModel.commands.collect { command ->
            when (command) {
                is RecordingCommand.Start -> recorder.start(
                    displayName = command.displayName,
                    onFinalized = viewModel::onRecordingFinalized,
                    onError = viewModel::onRecordingError,
                )
                RecordingCommand.Stop -> recorder.stop()
            }
        }
    }

    // Hardware Back: stop a live recording / abort a countdown first; otherwise
    // leave the screen.
    BackHandler {
        when (uiState) {
            is RecordingState.Recording -> viewModel.stopRecording()
            is RecordingState.Countdown -> viewModel.onRecordButton()
            else -> onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1 — live camera feed fills the screen.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    controller = recorder.controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
        )

        // 2 — legibility scrim: darken top & bottom so white text and the
        // controls read over a bright camera frame without dimming the face.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.40f),
                        0.18f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.70f),
                    ),
                ),
        )

        // 3 — the scrolling teleprompter script (speaker colour-coding, section
        // banners, production-cue styling, eye-line markers, edge fades, mirror).
        TeleprompterOverlay(
            script = script,
            wpm = wpm,
            fontSize = fontSize,
            opacity = opacity,
            mirror = mirror,
            scrolling = uiState is RecordingState.Recording,
        )

        // 4 — top row: exit + live recording indicator.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    when (uiState) {
                        is RecordingState.Recording -> viewModel.stopRecording()
                        else -> onBack()
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Close recording mode" },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            (uiState as? RecordingState.Recording)?.let { rec ->
                RecordingIndicator(elapsedMs = rec.elapsedMs)
            }
        }

        // 5 — countdown number, centered.
        (uiState as? RecordingState.Countdown)?.let { cd ->
            Text(
                text = cd.secondsLeft.toString(),
                color = Color.White,
                fontSize = 120.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { contentDescription = "Recording in ${cd.secondsLeft}" },
            )
        }

        // 6 — bottom controls (hidden while a result card is up).
        if (uiState !is RecordingState.Done && uiState !is RecordingState.Error) {
            BottomControls(
                state = uiState,
                opacity = opacity,
                mirror = mirror,
                canFlip = uiState !is RecordingState.Recording && uiState !is RecordingState.Saving,
                onOpacityChange = viewModel::setOpacity,
                onFlip = viewModel::flipCamera,
                onToggleMirror = viewModel::toggleMirror,
                onFontSmaller = { viewModel.adjustFontSize(-RecordingViewModel.FONT_STEP_SP) },
                onFontLarger = { viewModel.adjustFontSize(RecordingViewModel.FONT_STEP_SP) },
                onRecordButton = viewModel::onRecordButton,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 7 — result overlays.
        (uiState as? RecordingState.Done)?.let { done ->
            ResultCard(
                title = "Saved to your gallery",
                body = "Your clip is in Movies / Candela — ready to share.",
                primaryLabel = "Done",
                onPrimary = onBack,
                secondaryLabel = "Record again",
                onSecondary = viewModel::reset,
                tertiaryLabel = "View",
                onTertiary = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(done.uri, "video/*")
                                addFlags(
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_ACTIVITY_NEW_TASK,
                                )
                            },
                        )
                    }
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        (uiState as? RecordingState.Error)?.let { err ->
            ResultCard(
                title = "Recording failed",
                body = err.message,
                primaryLabel = "Try again",
                onPrimary = viewModel::reset,
                secondaryLabel = "Back",
                onSecondary = onBack,
                tertiaryLabel = null,
                onTertiary = {},
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Red dot + mm:ss elapsed, in a translucent pill — the live "REC" cue. */
@Composable
private fun RecordingIndicator(elapsedMs: Long) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.Red),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatElapsed(elapsedMs),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BottomControls(
    state: RecordingState,
    opacity: Float,
    mirror: Boolean,
    canFlip: Boolean,
    onOpacityChange: (Float) -> Unit,
    onFlip: () -> Unit,
    onToggleMirror: () -> Unit,
    onFontSmaller: () -> Unit,
    onFontLarger: () -> Unit,
    onRecordButton: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Font size (A− / A+) + mirror — design cues from the show teleprompter.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlChip(label = "A−", contentDesc = "Smaller text", onClick = onFontSmaller)
            ControlChip(label = "A+", contentDesc = "Larger text", onClick = onFontLarger)
            ControlChip(
                label = "Mirror",
                icon = Icons.Filled.Flip,
                active = mirror,
                contentDesc = if (mirror) "Mirror on" else "Mirror off",
                onClick = onToggleMirror,
            )
        }

        Spacer(modifier = Modifier.size(14.dp))

        // Text-opacity slider (30–100%), in a translucent rail.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Text", color = Color.White, fontSize = 13.sp)
            Slider(
                value = opacity,
                onValueChange = onOpacityChange,
                valueRange = RecordingViewModel.MIN_OPACITY..RecordingViewModel.MAX_OPACITY,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .semantics { contentDescription = "Script opacity" },
            )
        }

        Spacer(modifier = Modifier.size(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: flip camera.
            IconButton(
                onClick = onFlip,
                enabled = canFlip,
                modifier = Modifier
                    .size(56.dp)
                    .semantics { contentDescription = "Flip camera" },
            ) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = null,
                    tint = if (canFlip) Color.White else Color.White.copy(alpha = 0.4f),
                )
            }

            // Center: the record / stop button (or a spinner while saving).
            when (state) {
                is RecordingState.Saving -> CircularProgressIndicator(color = Color.White)
                else -> RecordButton(
                    recording = state is RecordingState.Recording,
                    onClick = onRecordButton,
                )
            }

            // Right: spacer to keep the record button centered.
            Spacer(modifier = Modifier.size(56.dp))
        }
    }
}

/** A translucent rounded control pill (font A−/A+, mirror toggle). Highlights
 *  when [active]. */
@Composable
private fun ControlChip(
    label: String,
    contentDesc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    active: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Color.White.copy(alpha = 0.30f) else Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = contentDesc },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(text = label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The shutter. A white ring with a red fill; the fill morphs from a circle
 * (idle / ready) to a rounded square (recording → tap to stop), the universal
 * camera record/stop affordance.
 */
@Composable
private fun RecordButton(
    recording: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (recording) "Stop recording" else "Start recording"
            },
        contentAlignment = Alignment.Center,
    ) {
        // White ring.
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(if (recording) 30.dp else 58.dp)
                    .clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
                    .background(Color.Red),
            )
        }
    }
}

/** Centered result/error card with up to three actions. */
@Composable
private fun ResultCard(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    tertiaryLabel: String?,
    onTertiary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier.padding(32.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(spacing.sm))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(spacing.lg))
            Button(
                onClick = onPrimary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(primaryLabel)
            }
            Spacer(modifier = Modifier.size(spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
                if (tertiaryLabel != null) {
                    TextButton(onClick = onTertiary) { Text(tertiaryLabel) }
                }
            }
        }
    }
}

/** Permission rationale shown when CAMERA / RECORD_AUDIO aren't granted. */
@Composable
private fun PermissionRationale(
    onGrant: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Camera & microphone needed",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(spacing.sm))
            Text(
                text = "Recording mode films you reading the script over the live " +
                    "camera. Grant camera and microphone access to record video " +
                    "with sound — nothing is captured until you tap record.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(spacing.lg))
            Button(onClick = onGrant) { Text("Grant access") }
            Spacer(modifier = Modifier.size(spacing.xs))
            TextButton(onClick = onBack) { Text("Not now") }
        }
    }
}

private fun hasPermission(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** mm:ss from elapsed millis (caps minutes at two digits; Shorts are short). */
private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
