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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import java.io.File
import java.util.Locale

/**
 * Issue #1513 — multi-page document scanner → clean shareable PDF
 * ("no scanner at home"). The capture foundation of the benefits
 * paperwork companion (epic #1520).
 *
 * Two capture paths:
 *  1. **ML Kit Document Scanner** (primary) — Play-services capture UI
 *     with edge detection, perspective crop, shadow removal, multipage.
 *     Runs on-device; needs no camera permission (Play services owns the
 *     camera). Returns cleaned page images.
 *  2. **Gallery pick** (fallback) — the system Photo Picker, for
 *     de-Googled devices where the scanner client can't start, or for
 *     photos already on the device.
 *
 * Captured pages accumulate in [DocScanViewModel]; the user reorders /
 * deletes them in a **list** (the TalkBack-friendly alternative to a
 * spatial drag grid), names the document, and exports one compact PDF
 * via the on-device [`in`.jphe.storyvox.data.docs.DocPdfExporter] seam.
 * The finished PDF is shared through the app FileProvider with
 * `ACTION_SEND`. Nothing about the document leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocScanScreen(
    onNavigateBack: () -> Unit,
    viewModel: DocScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val shareChooserTitle = stringResource(R.string.docs_scan_share_chooser)

    // ── ML Kit Document Scanner launcher ───────────────────────────────
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uris = scanResult?.pages?.mapNotNull { it.imageUri?.toString() } ?: emptyList()
            viewModel.onPagesCaptured(uris)
        }
    }

    // ── Gallery multi-pick fallback (permissionless Photo Picker) ───────
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris: List<Uri> ->
        viewModel.onPagesCaptured(uris.map { it.toString() })
    }

    fun launchScanner() {
        val activity = context.findActivity()
        if (activity == null) {
            viewModel.onScannerUnavailable()
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { viewModel.onScannerUnavailable() }
    }

    // One-shot: fire the share sheet when an export completes.
    LaunchedEffect(state.shareRequest) {
        state.shareRequest?.let { ready ->
            sharePdf(context, ready.filePath, ready.fileName, shareChooserTitle)
            viewModel.onShareHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.docs_scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.docs_scan_back_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = spacing.md),
        ) {
            item {
                Text(
                    stringResource(R.string.docs_scan_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Capture actions ────────────────────────────────────────
            item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    BrassButton(
                        label = stringResource(R.string.docs_scan_button),
                        onClick = { launchScanner() },
                        variant = BrassButtonVariant.Primary,
                        enabled = !state.isExporting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrassButton(
                            label = stringResource(R.string.docs_scan_pick_gallery),
                            onClick = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            variant = BrassButtonVariant.Secondary,
                            enabled = !state.isExporting,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.scannerUnavailable) {
                item { InfoCard(text = stringResource(R.string.docs_scan_scanner_unavailable)) }
            }

            // Privacy reassurance (invariant #1 — on-device only).
            item { InfoCard(text = stringResource(R.string.docs_scan_privacy_note)) }

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

            if (state.pages.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.docs_scan_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChanged,
                        label = { Text(stringResource(R.string.docs_scan_title_label)) },
                        singleLine = true,
                        enabled = !state.isExporting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        pluralStringResource(
                            R.plurals.docs_scan_pages_ready,
                            state.pageCount,
                            state.pageCount,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        // a11y — re-announce the tally after each capture /
                        // reorder / delete so a non-visual user hears it.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                    PageRow(
                        pageNumber = index + 1,
                        uri = page.uri,
                        isFirst = index == 0,
                        isLast = index == state.pages.lastIndex,
                        enabled = !state.isExporting,
                        onMoveUp = { viewModel.movePageUp(page.id) },
                        onMoveDown = { viewModel.movePageDown(page.id) },
                        onRemove = { viewModel.removePage(page.id) },
                    )
                }
                item {
                    BrassButton(
                        label = stringResource(
                            if (state.isExporting) R.string.docs_scan_exporting else R.string.docs_scan_export,
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
                                    stringResource(
                                        R.string.docs_scan_exported,
                                        ready.fileName,
                                        formatBytes(ready.byteSize),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                BrassButton(
                                    label = stringResource(R.string.docs_scan_share_again),
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
}

/** One page row: thumbnail + "Page N" + reorder/delete controls. A list
 *  (not a spatial grid) so it is fully reachable and labeled under
 *  TalkBack (invariant #4). */
@Composable
private fun PageRow(
    pageNumber: Int,
    uri: String,
    isFirst: Boolean,
    isLast: Boolean,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val spacing = LocalSpacing.current
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
            AsyncImage(
                model = uri,
                contentDescription = null, // decorative; the label text carries the page number
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp, 64.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Text(
                stringResource(R.string.docs_scan_page_label, pageNumber),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onMoveUp, enabled = enabled && !isFirst) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.docs_scan_move_up_cd, pageNumber),
                )
            }
            IconButton(onClick = onMoveDown, enabled = enabled && !isLast) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.docs_scan_move_down_cd, pageNumber),
                )
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.docs_scan_remove_cd, pageNumber),
                )
            }
        }
    }
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

/** Unwrap the Activity from the Compose LocalContext (needed by ML Kit's
 *  getStartScanIntent). */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Share the finished PDF via the app FileProvider (mirrors
 *  CreateAudiobookSheet.shareAudiobook). */
private fun sharePdf(context: Context, filePath: String, fileName: String, chooserTitle: String) {
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

/** Human-readable byte size ("245 KB" / "1.2 MB"). */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> "${bytes / 1_000L} KB"
    else -> "$bytes B"
}
