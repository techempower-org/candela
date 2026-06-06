package `in`.jphe.storyvox.feature.library

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.text.KeyboardOptions
import `in`.jphe.storyvox.playback.audiobook.AudiobookExportStatus
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import java.io.File

/**
 * "Make your own audiobook" sheet (issue #1003). One screen, three phases:
 *  1. **Compose** — paste/type text, name the book + author, pick a voice
 *     (the active/first voice is pre-selected so the user can one-tap through).
 *  2. **Rendering** — a determinate progress bar driven by the WorkManager
 *     job's published fraction.
 *  3. **Done** — Share (ACTION_SEND) / Save… (SAF) the finished `.m4b`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAudiobookSheet(
    onDismiss: () -> Unit,
    viewModel: CreateAudiobookViewModel = hiltViewModel(),
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val uiState by viewModel.uiState.collectAsState()
    val voices by viewModel.voices.collectAsState()
    val status by viewModel.exportStatus.collectAsState()

    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var selectedVoiceId by remember { mutableStateOf<String?>(null) }

    // Pre-select a sensible default voice once the roster loads.
    LaunchedEffect(voices) {
        if (selectedVoiceId == null) selectedVoiceId = voices.firstOrNull()?.id
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/mp4"),
    ) { destination ->
        val done = status as? AudiobookExportStatus.Succeeded
        if (destination != null && done != null) {
            runCatching {
                context.contentResolver.openOutputStream(destination)?.use { out ->
                    File(done.filePath).inputStream().use { it.copyTo(out) }
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            when (val s = status) {
                is AudiobookExportStatus.Succeeded -> DonePhase(
                    fileName = s.fileName,
                    chapterCount = s.chapterCount,
                    warnings = s.warnings,
                    onShare = { shareAudiobook(context, File(s.filePath), s.fileName) },
                    onSave = { saveLauncher.launch(s.fileName) },
                    onDone = { viewModel.reset(); onDismiss() },
                )

                is AudiobookExportStatus.Running -> RenderingPhase(progress = s.progress)

                is AudiobookExportStatus.Failed -> FailedPhase(
                    message = s.message,
                    onRetry = { viewModel.reset() },
                )

                AudiobookExportStatus.Idle -> ComposePhase(
                    title = title,
                    author = author,
                    text = text,
                    voices = voices,
                    selectedVoiceId = selectedVoiceId,
                    isStarting = uiState.isStarting,
                    error = uiState.error,
                    onTitle = { title = it },
                    onAuthor = { author = it },
                    onText = { text = it },
                    onPaste = { readClipboard(context)?.let { c -> if (c.isNotBlank()) text = c } },
                    onPickVoice = { selectedVoiceId = it },
                    onCreate = {
                        viewModel.start(
                            text = text,
                            title = title,
                            author = author,
                            voiceId = selectedVoiceId,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ComposePhase(
    title: String,
    author: String,
    text: String,
    voices: List<`in`.jphe.storyvox.playback.voice.UiVoiceInfo>,
    selectedVoiceId: String?,
    isStarting: Boolean,
    error: String?,
    onTitle: (String) -> Unit,
    onAuthor: (String) -> Unit,
    onText: (String) -> Unit,
    onPaste: () -> Unit,
    onPickVoice: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Text("Make your own audiobook", style = MaterialTheme.typography.titleLarge)
    Text(
        "Paste or type your text, pick a voice, and Candela narrates it into a " +
            "chaptered audiobook you can keep and share — all offline, no account.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = title,
        onValueChange = onTitle,
        label = { Text("Title") },
        singleLine = true,
        enabled = !isStarting,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = author,
        onValueChange = onAuthor,
        label = { Text("Author (optional)") },
        singleLine = true,
        enabled = !isStarting,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text("Your text") },
        placeholder = { Text("Paste a chapter, an article, a story…") },
        enabled = !isStarting,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 240.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        BrassButton(
            label = "Paste from clipboard",
            onClick = onPaste,
            variant = BrassButtonVariant.Text,
            enabled = !isStarting,
        )
    }

    if (voices.isNotEmpty()) {
        Text("Voice", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(voices, key = { it.id }) { v ->
                AssistChip(
                    onClick = { onPickVoice(v.id) },
                    label = { Text(v.displayName) },
                    modifier = Modifier.selectable(
                        selected = v.id == selectedVoiceId,
                        role = Role.RadioButton,
                        onClick = { onPickVoice(v.id) },
                    ),
                    colors = if (v.id == selectedVoiceId) {
                        androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        )
                    } else {
                        androidx.compose.material3.AssistChipDefaults.assistChipColors()
                    },
                )
            }
        }
    } else {
        Text(
            "Install a voice in the Voices tab to narrate your audiobook.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (error != null) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    BrassButton(
        label = "Create audiobook",
        onClick = onCreate,
        variant = BrassButtonVariant.Primary,
        enabled = text.isNotBlank() && voices.isNotEmpty() && !isStarting,
        loading = isStarting,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RenderingPhase(progress: Float) {
    val spacing = LocalSpacing.current
    Text("Creating your audiobook…", style = MaterialTheme.typography.titleMedium)
    Text(
        "Narrating chapters offline. You can leave this screen — we'll keep going " +
            "in the background.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (progress <= 0f) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = spacing.sm))
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        )
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DonePhase(
    fileName: String,
    chapterCount: Int,
    warnings: List<String>,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDone: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Text("Your audiobook is ready", style = MaterialTheme.typography.titleMedium)
    Text(
        "$fileName · $chapterCount chapter${if (chapterCount == 1) "" else "s"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    warnings.forEach { w ->
        Text("• $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        BrassButton(
            label = "Save…",
            onClick = onSave,
            variant = BrassButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
        BrassButton(
            label = "Share",
            onClick = onShare,
            variant = BrassButtonVariant.Primary,
            modifier = Modifier.weight(1f),
        )
    }
    BrassButton(
        label = "Done",
        onClick = onDone,
        variant = BrassButtonVariant.Text,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FailedPhase(message: String, onRetry: () -> Unit) {
    Text("Couldn't create the audiobook", style = MaterialTheme.typography.titleMedium)
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    BrassButton(
        label = "Try again",
        onClick = onRetry,
        variant = BrassButtonVariant.Primary,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun shareAudiobook(context: Context, file: File, fileName: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share audiobook").also {
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
}

private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.text?.toString()
}
