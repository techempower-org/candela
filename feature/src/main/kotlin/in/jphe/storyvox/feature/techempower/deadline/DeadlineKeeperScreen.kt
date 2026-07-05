package `in`.jphe.storyvox.feature.techempower.deadline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Issue #1515 — notice deadline keeper screen.
 *
 * Photograph (or pick) a benefits letter → on-device OCR + date
 * extraction surfaces candidate deadlines → the user confirms one, edits
 * the reminder copy, and Candela schedules local T-7 / T-2 / day-of
 * notifications. Nothing leaves the device; the whole path works in
 * airplane mode.
 *
 * Accessibility is the audience (invariant #4): every affordance carries
 * a TalkBack label, "reading…" / "reminder set" / error states are live
 * regions, and the candidate + reminder lists ARE the screen-reader-
 * friendly alternative to any spatial overlay (there is no overlay — it's
 * a plain vertical list top to bottom).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlineKeeperScreen(
    onNavigateBack: () -> Unit,
    viewModel: DeadlineKeeperViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    // Re-read the exact-alarm grant whenever we (re)enter the screen —
    // the user may have toggled it in system settings.
    LaunchedEffect(Unit) { viewModel.refreshExactAlarmState() }

    // One-shot success feedback. A Toast is announced by TalkBack, so it
    // doubles as the accessibility confirmation that a reminder was set.
    val scheduledMsg = stringResource(R.string.deadline_scheduled_announce)
    LaunchedEffect(state.justScheduled) {
        if (state.justScheduled) {
            Toast.makeText(context, scheduledMsg, Toast.LENGTH_SHORT).show()
            viewModel.consumeScheduledSignal()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.deadline_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.deadline_back_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = spacing.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            val draft = state.draft
            if (draft != null) {
                DraftEditor(
                    draft = draft,
                    exactAlarmsAllowed = state.exactAlarmsAllowed,
                    onLabelChange = viewModel::onDraftLabelChanged,
                    onBodyChange = viewModel::onDraftBodyChanged,
                    onDeadlineChange = viewModel::onDraftDeadlineChanged,
                    onOffsetsChange = viewModel::onDraftOffsetsChanged,
                    onConfirm = viewModel::confirmDraft,
                    onCancel = viewModel::cancelDraft,
                    onOpenExactSettings = { context.openExactAlarmSettings() },
                )
            } else {
                Text(
                    stringResource(R.string.deadline_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                CaptureSection(
                    isBusy = state.isRecognizing,
                    onCaptured = { bytes, rotation -> viewModel.onImageCaptured(bytes, rotation) },
                )

                if (state.isRecognizing) {
                    StatusText(stringResource(R.string.deadline_recognizing), assertive = true)
                }

                state.error?.let { err ->
                    ErrorCard(err)
                }

                if (state.noDatesFound) {
                    InfoCard(stringResource(R.string.deadline_no_dates))
                }

                if (state.candidates.isNotEmpty()) {
                    CandidatesSection(
                        candidates = state.candidates,
                        onSelect = { candidate ->
                            viewModel.selectCandidate(
                                candidate = candidate,
                                label = context.getString(R.string.deadline_scan_label),
                                defaultBody = context.getString(
                                    R.string.deadline_default_body,
                                    context.getString(R.string.deadline_scan_label),
                                    candidate.date.formattedMedium(),
                                ),
                            )
                        },
                    )
                }

                PresetsSection(
                    presets = viewModel.presets,
                    onSelect = { preset ->
                        val label = context.getString(programNameRes(preset.programId))
                        viewModel.selectPreset(
                            preset = preset,
                            label = label,
                            defaultBody = context.getString(
                                R.string.deadline_default_body,
                                label,
                                preset.suggestedDeadline(LocalDate.now()).formattedMedium(),
                            ),
                        )
                    },
                )

                RemindersSection(
                    reminders = state.reminders,
                    onDelete = viewModel::deleteReminder,
                )
            }
        }
    }
}

// ─────────────────────────── Draft editor ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DraftEditor(
    draft: ReminderDraft,
    exactAlarmsAllowed: Boolean,
    onLabelChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onDeadlineChange: (LocalDate) -> Unit,
    onOffsetsChange: (List<Int>) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onOpenExactSettings: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var showDatePicker by remember { mutableStateOf(false) }

    Text(
        stringResource(R.string.deadline_draft_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = draft.label,
        onValueChange = onLabelChange,
        label = { Text(stringResource(R.string.deadline_field_label)) },
        singleLine = true,
        isError = draft.label.isBlank(),
        modifier = Modifier.fillMaxWidth(),
    )

    // Deadline row: the date + a "change date" affordance.
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.deadline_field_deadline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    draft.deadline.formattedFull(),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(onClick = { showDatePicker = true }) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = stringResource(R.string.deadline_change_date),
                )
            }
        }
    }

    OutlinedTextField(
        value = draft.body,
        onValueChange = onBodyChange,
        label = { Text(stringResource(R.string.deadline_field_body)) },
        supportingText = { Text(stringResource(R.string.deadline_body_privacy_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )

    // Offset chips — which days-before to remind.
    Text(
        stringResource(R.string.deadline_offsets_title),
        style = MaterialTheme.typography.labelLarge,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        OffsetChip(7, R.string.deadline_offset_week, draft.offsetsDays, onOffsetsChange)
        OffsetChip(2, R.string.deadline_offset_2days, draft.offsetsDays, onOffsetsChange)
        OffsetChip(0, R.string.deadline_offset_day, draft.offsetsDays, onOffsetsChange)
    }

    if (!exactAlarmsAllowed) {
        ExactAlarmHint(onOpenExactSettings)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        BrassButton(
            label = stringResource(R.string.deadline_cancel),
            onClick = onCancel,
            variant = BrassButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
        BrassButton(
            label = stringResource(R.string.deadline_save),
            onClick = onConfirm,
            variant = BrassButtonVariant.Primary,
            enabled = draft.canConfirm && draft.offsetsDays.isNotEmpty(),
            modifier = Modifier.weight(1f),
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = draft.deadline
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onDeadlineChange(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.deadline_date_picker_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.deadline_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OffsetChip(
    offset: Int,
    labelRes: Int,
    selected: List<Int>,
    onChange: (List<Int>) -> Unit,
) {
    val isOn = offset in selected
    FilterChip(
        selected = isOn,
        onClick = {
            onChange(if (isOn) selected - offset else selected + offset)
        },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun ExactAlarmHint(onOpenSettings: () -> Unit) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                stringResource(R.string.deadline_exact_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.deadline_exact_open_settings))
            }
        }
    }
}

// ─────────────────────────── Capture ───────────────────────────

@Composable
private fun CaptureSection(
    isBusy: Boolean,
    onCaptured: (bytes: ByteArray, rotationDegrees: Int) -> Unit,
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraDenied by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        cameraDenied = !granted
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let {
            val bytes = readImageBytes(context, it)
            if (bytes != null) onCaptured(bytes, 0)
        }
    }

    when {
        hasCameraPermission -> {
            CameraCaptureBox(enabled = !isBusy, onCaptured = onCaptured)
        }
        cameraDenied -> InfoCard(stringResource(R.string.deadline_camera_denied))
        else -> {
            InfoCard(stringResource(R.string.deadline_camera_rationale))
            BrassButton(
                label = stringResource(R.string.deadline_enable_camera),
                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                variant = BrassButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrassButton(
            label = stringResource(R.string.deadline_pick_gallery),
            onClick = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            variant = BrassButtonVariant.Secondary,
            enabled = !isBusy,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CameraCaptureBox(
    enabled: Boolean,
    onCaptured: (bytes: ByteArray, rotationDegrees: Int) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spacing = LocalSpacing.current
    val captureCd = stringResource(R.string.deadline_capture_button_cd)

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
        )
        BrassButton(
            label = stringResource(R.string.deadline_capture_button),
            onClick = { takePicture(context, controller, onCaptured) },
            variant = BrassButtonVariant.Primary,
            enabled = enabled,
            modifier = Modifier
                .padding(bottom = spacing.md)
                .semantics { contentDescription = captureCd },
        )
    }
}

// ─────────────────────────── Candidates ───────────────────────────

@Composable
private fun CandidatesSection(
    candidates: List<DateCandidate>,
    onSelect: (DateCandidate) -> Unit,
) {
    val spacing = LocalSpacing.current
    Text(
        stringResource(R.string.deadline_candidates_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        candidates.forEach { candidate ->
            val pastSuffix = if (candidate.isPast) " " + stringResource(R.string.deadline_candidate_past) else ""
            val cd = stringResource(
                R.string.deadline_candidate_cd,
                candidate.date.formattedFull() + pastSuffix,
                candidate.cue ?: "",
            )
            SelectableCard(contentDescription = cd, onClick = { onSelect(candidate) }) {
                Text(
                    candidate.date.formattedFull() + pastSuffix,
                    style = MaterialTheme.typography.titleMedium,
                )
                candidate.cue?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    candidate.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─────────────────────────── Presets ───────────────────────────

@Composable
private fun PresetsSection(
    presets: List<DeadlineRecertPreset>,
    onSelect: (DeadlineRecertPreset) -> Unit,
) {
    if (presets.isEmpty()) return
    val spacing = LocalSpacing.current
    Text(
        stringResource(R.string.deadline_presets_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        presets.forEach { preset ->
            val name = stringResource(programNameRes(preset.programId))
            val cd = stringResource(R.string.deadline_preset_cd, name)
            SelectableCard(contentDescription = cd, onClick = { onSelect(preset) }) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.deadline_preset_confirm_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─────────────────────────── Reminders list ───────────────────────────

@Composable
private fun RemindersSection(
    reminders: List<DeadlineReminder>,
    onDelete: (DeadlineReminder) -> Unit,
) {
    val spacing = LocalSpacing.current
    Text(
        stringResource(R.string.deadline_reminders_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    if (reminders.isEmpty()) {
        Text(
            stringResource(R.string.deadline_reminders_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        reminders.forEach { reminder ->
            val itemCd = stringResource(
                R.string.deadline_reminder_item_cd,
                reminder.label,
                reminder.deadline.formattedFull(),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clearAndSetSemantics { contentDescription = itemCd },
                    ) {
                        Text(reminder.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            reminder.deadline.formattedFull(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onDelete(reminder) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(
                                R.string.deadline_delete_cd,
                                reminder.label,
                            ),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Shared bits ───────────────────────────

@Composable
private fun SelectableCard(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
            content = content,
        )
    }
}

@Composable
private fun StatusText(text: String, assertive: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics {
            liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
        },
    )
}

@Composable
private fun InfoCard(text: String) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(spacing.md),
        )
    }
}

@Composable
private fun ErrorCard(text: String) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(spacing.md),
        )
    }
}

// ─────────────────────────── Helpers ───────────────────────────

/** Map a program id to its localized display-name resource. */
private fun programNameRes(programId: String?): Int = when (programId) {
    DeadlinePrograms.LIFELINE -> R.string.deadline_program_lifeline
    DeadlinePrograms.CALFRESH -> R.string.deadline_program_calfresh
    DeadlinePrograms.MEDI_CAL -> R.string.deadline_program_medi_cal
    DeadlinePrograms.SUN_BUCKS -> R.string.deadline_program_sun_bucks
    else -> R.string.deadline_program_other
}

private fun LocalDate.formattedMedium(): String =
    format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

private fun LocalDate.formattedFull(): String =
    format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))

/** Deep-link into the system exact-alarm settings for this app (Android 12+). */
private fun Context.openExactAlarmSettings() {
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:$packageName")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }.onFailure {
        // Fall back to the app's notification / details settings.
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}

private fun takePicture(
    context: Context,
    controller: LifecycleCameraController,
    onCaptured: (bytes: ByteArray, rotationDegrees: Int) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(context)
    controller.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bytes = image.toJpegBytes()
                image.close()
                if (bytes != null) onCaptured(bytes, rotation)
            }

            override fun onError(exception: ImageCaptureException) {
                // Rare (camera busy); the user simply taps capture again.
            }
        },
    )
}

private fun androidx.camera.core.ImageProxy.toJpegBytes(): ByteArray? {
    val buffer = planes.firstOrNull()?.buffer ?: return null
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

private fun readImageBytes(context: Context, uri: Uri): ByteArray? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
    }.getOrNull()
