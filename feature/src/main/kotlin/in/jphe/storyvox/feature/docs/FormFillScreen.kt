package `in`.jphe.storyvox.feature.docs

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import `in`.jphe.storyvox.data.docs.NormPoint
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import java.io.File
import kotlin.math.roundToInt

/**
 * Issue #1512 — photo → fillable PDF. Scan/pick a paper form, place
 * fields on it (tap-anywhere text, checkmarks, a drawn signature), and
 * export a **flattened** PDF (verdict: flatten, not AcroForm) that looks
 * like the completed paper form.
 *
 * Two ways to reach every field, per invariant #4 (accessibility):
 *  - **Spatial** — tap the page to place a field where it belongs; placed
 *    fields render as overlays on the page image.
 *  - **List** — a linear, TalkBack-navigable list of every placed field
 *    with inline editing / removal / signature-redraw. This is the
 *    screen-reader-equal path the issue explicitly calls for.
 *
 * Everything is on-device (image, typed values, signature, PDF
 * composition); no network → airplane-mode safe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormFillScreen(
    onNavigateBack: () -> Unit,
    viewModel: FormFillViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val shareChooserTitle = stringResource(R.string.form_fill_share_chooser)

    var signatureTargetId by remember { mutableStateOf<Int?>(null) }

    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scan?.pages?.firstOrNull()?.imageUri?.let { viewModel.onPageCaptured(it.toString()) }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let { viewModel.onPageCaptured(it.toString()) }
    }

    fun launchScanner() {
        val activity = context.findActivityForForm() ?: return
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(1)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener {
                scannerLauncher.launch(IntentSenderRequest.Builder(it).build())
            }
            .addOnFailureListener { /* fall back to gallery */ }
    }

    LaunchedEffect(state.shareRequest) {
        state.shareRequest?.let { ready ->
            shareFilledPdf(context, ready.filePath, ready.fileName, shareChooserTitle)
            viewModel.onShareHandled()
        }
    }

    // Placing a signature opens the draw pad on that new field.
    LaunchedEffect(state.pendingSignatureId) {
        state.pendingSignatureId?.let {
            signatureTargetId = it
            viewModel.consumePendingSignature()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.form_fill_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.form_fill_back_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = spacing.md),
        ) {
            item {
                Text(
                    stringResource(R.string.form_fill_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.hasPage) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        BrassButton(
                            label = stringResource(R.string.form_fill_scan),
                            onClick = { launchScanner() },
                            variant = BrassButtonVariant.Primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        BrassButton(
                            label = stringResource(R.string.form_fill_pick_gallery),
                            onClick = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            variant = BrassButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item { InfoCardForm(text = stringResource(R.string.form_fill_privacy_note)) }
            } else {
                // ── Tool selector ──────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        ToolButton(R.string.form_fill_tool_text, state.activeTool == FillTool.Text, Modifier.weight(1f)) {
                            viewModel.setTool(FillTool.Text)
                        }
                        ToolButton(R.string.form_fill_tool_check, state.activeTool == FillTool.Check, Modifier.weight(1f)) {
                            viewModel.setTool(FillTool.Check)
                        }
                        ToolButton(R.string.form_fill_tool_signature, state.activeTool == FillTool.Signature, Modifier.weight(1f)) {
                            viewModel.setTool(FillTool.Signature)
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.form_fill_tap_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ── Spatial page + overlays (tap to place) ─────────────
                item {
                    PageCanvas(
                        pageUri = state.pageImageUri!!,
                        fields = state.fields,
                        onTap = { nx, ny -> viewModel.onTapPage(nx, ny) },
                    )
                }

                // ── Field LIST (accessibility-equal editing surface) ───
                item {
                    Text(
                        stringResource(R.string.form_fill_fields_header, state.fields.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (state.fields.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.form_fill_no_fields),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.fields, key = { it.id }) { field ->
                        FieldRow(
                            field = field,
                            onTextChange = { viewModel.updateText(field.id, it) },
                            onRemove = { viewModel.removeField(field.id) },
                            onRedrawSignature = { signatureTargetId = field.id },
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChanged,
                        label = { Text(stringResource(R.string.form_fill_title_label)) },
                        singleLine = true,
                        enabled = !state.isExporting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                state.error?.let { err ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Assertive },
                        ) {
                            Text(
                                err,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(spacing.md),
                            )
                        }
                    }
                }

                item {
                    BrassButton(
                        label = stringResource(
                            if (state.isExporting) R.string.form_fill_exporting else R.string.form_fill_export,
                        ),
                        onClick = viewModel::export,
                        variant = BrassButtonVariant.Primary,
                        enabled = state.canExport,
                        loading = state.isExporting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.exportResult?.let { ready ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Column(
                                modifier = Modifier.padding(spacing.md),
                                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                Text(
                                    stringResource(R.string.form_fill_exported, ready.fileName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                BrassButton(
                                    label = stringResource(R.string.form_fill_share_again),
                                    onClick = viewModel::shareAgain,
                                    variant = BrassButtonVariant.Secondary,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Signature draw pad.
    signatureTargetId?.let { id ->
        SignaturePadDialog(
            onSave = { strokes ->
                viewModel.setSignatureStrokes(id, strokes)
                signatureTargetId = null
            },
            onDismiss = { signatureTargetId = null },
        )
    }
}

@Composable
private fun ToolButton(labelRes: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BrassButton(
        label = stringResource(labelRes),
        onClick = onClick,
        variant = if (selected) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
        modifier = modifier,
    )
}

/** The form page with tap-to-place and rendered overlays. */
@Composable
private fun PageCanvas(
    pageUri: String,
    fields: List<FormField>,
    onTap: (nx: Float, ny: Float) -> Unit,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val pageContentDesc = stringResource(R.string.form_fill_page_cd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onTap(offset.x / boxSize.width, offset.y / boxSize.height)
                    }
                }
            }
            // The page image itself is decorative for TalkBack — the field
            // LIST below is the accessible editing surface, so this spatial
            // canvas is not a focus trap.
            .semantics { contentDescription = pageContentDesc },
    ) {
        AsyncImage(
            model = pageUri,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
        val w = boxSize.width
        val h = boxSize.height
        if (w > 0 && h > 0) {
            // Text + checkmark overlays as offset composables.
            fields.forEach { field ->
                when (field) {
                    is FormField.Text -> if (field.text.isNotBlank()) {
                        Text(
                            field.text,
                            color = Color.Black,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .offset {
                                    IntOffset((field.x * w).roundToInt(), (field.y * h).roundToInt())
                                }
                                .background(Color.White.copy(alpha = 0.6f)),
                        )
                    }

                    is FormField.Check -> Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier
                            .offset {
                                IntOffset((field.x * w).roundToInt(), (field.y * h).roundToInt())
                            }
                            .size(18.dp),
                    )

                    is FormField.Signature -> Unit // drawn on the canvas below
                }
            }
            // Signatures on one full-box canvas, mapped to absolute coords.
            Canvas(modifier = Modifier.matchParentSize()) {
                fields.filterIsInstance<FormField.Signature>().forEach { sig ->
                    val bl = sig.x * size.width
                    val bt = sig.y * size.height
                    val bw = sig.widthFraction * size.width
                    val bh = sig.heightFraction * size.height
                    sig.strokes.forEach { stroke ->
                        for (i in 1 until stroke.size) {
                            drawLine(
                                color = Color.Black,
                                start = Offset(bl + stroke[i - 1].x * bw, bt + stroke[i - 1].y * bh),
                                end = Offset(bl + stroke[i].x * bw, bt + stroke[i].y * bh),
                                strokeWidth = 3f,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One row in the accessible field list. */
@Composable
private fun FieldRow(
    field: FormField,
    onTextChange: (String) -> Unit,
    onRemove: () -> Unit,
    onRedrawSignature: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val removeCd = when (field) {
        is FormField.Text -> stringResource(R.string.form_fill_remove_text_cd)
        is FormField.Check -> stringResource(R.string.form_fill_remove_check_cd)
        is FormField.Signature -> stringResource(R.string.form_fill_remove_signature_cd)
    }
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
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            when (field) {
                is FormField.Text -> {
                    OutlinedTextField(
                        value = field.text,
                        onValueChange = onTextChange,
                        label = { Text(stringResource(R.string.form_fill_text_field_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                is FormField.Check -> {
                    Text(
                        stringResource(R.string.form_fill_checkmark_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }

                is FormField.Signature -> {
                    val drawn = field.strokes.any { it.size >= 2 }
                    Text(
                        stringResource(
                            if (drawn) R.string.form_fill_signature_drawn
                            else R.string.form_fill_signature_empty,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    BrassButton(
                        label = stringResource(R.string.form_fill_draw_signature),
                        onClick = onRedrawSignature,
                        variant = BrassButtonVariant.Secondary,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = removeCd,
                )
            }
        }
    }
}

/** Full-width draw pad; captures strokes normalized to the pad. */
@Composable
private fun SignaturePadDialog(
    onSave: (List<List<NormPoint>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val strokes: SnapshotStateList<List<NormPoint>> = remember { mutableStateListOf() }
    var current by remember { mutableStateOf<List<NormPoint>>(emptyList()) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val padCd = stringResource(R.string.form_fill_signature_pad_cd)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text(
                    stringResource(R.string.form_fill_signature_pad_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .onSizeChanged { padSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { off -> current = listOf(normFor(off, padSize)) },
                                onDrag = { change, _ -> current = current + normFor(change.position, padSize) },
                                onDragEnd = {
                                    if (current.size >= 2) strokes.add(current)
                                    current = emptyList()
                                },
                            )
                        }
                        .clearAndSetSemantics { contentDescription = padCd },
                ) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                        (strokes + listOf(current)).forEach { stroke ->
                            for (i in 1 until stroke.size) {
                                drawLine(
                                    color = Color.Black,
                                    start = Offset(stroke[i - 1].x * size.width, stroke[i - 1].y * size.height),
                                    end = Offset(stroke[i].x * size.width, stroke[i].y * size.height),
                                    strokeWidth = 5f,
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    BrassButton(
                        label = stringResource(R.string.form_fill_signature_clear),
                        onClick = { strokes.clear(); current = emptyList() },
                        variant = BrassButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    BrassButton(
                        label = stringResource(R.string.form_fill_signature_cancel),
                        onClick = onDismiss,
                        variant = BrassButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    BrassButton(
                        label = stringResource(R.string.form_fill_signature_save),
                        onClick = { onSave(strokes.toList()) },
                        variant = BrassButtonVariant.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCardForm(text: String) {
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

private fun normFor(offset: Offset, size: IntSize): NormPoint {
    val x = if (size.width > 0) (offset.x / size.width).coerceIn(0f, 1f) else 0f
    val y = if (size.height > 0) (offset.y / size.height).coerceIn(0f, 1f) else 0f
    return NormPoint(x, y)
}

private fun Context.findActivityForForm(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun shareFilledPdf(context: Context, filePath: String, fileName: String, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(filePath),
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, chooserTitle).also {
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
}
