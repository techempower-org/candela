package `in`.jphe.storyvox.feature.docs

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import `in`.jphe.storyvox.data.wallet.WalletDoc
import `in`.jphe.storyvox.data.wallet.WalletDocType
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import java.io.File

/**
 * Issue #1514 — "My Documents": the encrypted, device-credential-gated
 * on-device document wallet.
 *
 * The wallet is LOCKED until the user confirms their device credential
 * (fingerprint / face / PIN / pattern) via
 * [KeyguardManager.createConfirmDeviceCredentialIntent]; only then does
 * the ViewModel decrypt the list. (This gate works from a plain
 * `ComponentActivity` — the same approach as #1519 — so it needs no
 * `androidx.biometric` dependency and no extra permission.) Documents are
 * added from the scanner/gallery (encrypted at rest), shown with a
 * freshness/staleness hint, can be re-exported to a shareable PDF, and
 * answer "what does this prove?" from the verified program catalog.
 * Nothing leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    onNavigateBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val shareChooser = stringResource(R.string.wallet_share_chooser)
    val promptTitle = stringResource(R.string.wallet_unlock_title)
    val promptSubtitle = stringResource(R.string.wallet_unlock_subtitle)
    val noLockMessage = stringResource(R.string.wallet_no_device_lock)

    // Pending capture → save-to-wallet dialog.
    val pendingPages: SnapshotStateList<String> = remember { mutableStateListOf() }
    var showSaveDialog by remember { mutableStateOf(false) }
    var whatProvesFor by remember { mutableStateOf<WalletDoc?>(null) }

    // Device-credential (biometric / PIN / pattern) confirmation result.
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onUnlocked()
        else viewModel.onAuthFailed(null) // cancelled — stay locked
    }

    fun unlock() {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val intent = if (keyguard != null && keyguard.isDeviceSecure) {
            keyguard.createConfirmDeviceCredentialIntent(promptTitle, promptSubtitle)
        } else {
            null
        }
        if (intent != null) {
            credentialLauncher.launch(intent)
        } else {
            // No secure lock set: nothing to authenticate against. Data is
            // still encrypted at rest; open with a note.
            viewModel.onAuthFailed(noLockMessage)
            viewModel.onUnlocked()
        }
    }

    // Prompt once on first entry.
    LaunchedEffect(Unit) {
        if (!state.unlocked) unlock()
    }

    // Capture launchers for the add flow.
    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uris = scan?.pages?.mapNotNull { it.imageUri?.toString() } ?: emptyList()
            if (uris.isNotEmpty()) { pendingPages.clear(); pendingPages.addAll(uris); showSaveDialog = true }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            pendingPages.clear(); pendingPages.addAll(uris.map { it.toString() }); showSaveDialog = true
        }
    }

    fun launchScanner() {
        val activity = context.findActivity() ?: return
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { /* fall back to gallery */ }
    }

    LaunchedEffect(state.shareRequest) {
        state.shareRequest?.let { ready ->
            shareWalletPdf(context, ready.filePath, ready.fileName, shareChooser)
            viewModel.onShareHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wallet_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.wallet_back_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!state.unlocked) {
            LockedContent(
                modifier = Modifier.padding(innerPadding),
                error = state.error,
                onUnlock = { unlock() },
            )
        } else {
            UnlockedContent(
                modifier = Modifier.padding(innerPadding),
                state = state,
                onScan = { launchScanner() },
                onPickGallery = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onReExport = { doc -> viewModel.reExport(doc.id, doc.title) },
                onWhatProves = { doc -> whatProvesFor = doc },
                onDelete = { doc -> viewModel.delete(doc.id) },
            )
        }
    }

    if (showSaveDialog && pendingPages.isNotEmpty()) {
        SaveToWalletDialog(
            onSave = { type, title, note ->
                viewModel.addDocument(type, title, note, pendingPages.toList())
                pendingPages.clear()
                showSaveDialog = false
            },
            onDismiss = { pendingPages.clear(); showSaveDialog = false },
        )
    }

    whatProvesFor?.let { doc ->
        WhatDoesThisProveDialog(
            doc = doc,
            programs = viewModel.programsFor(doc.type),
            onDismiss = { whatProvesFor = null },
        )
    }
}

@Composable
private fun LockedContent(modifier: Modifier, error: String?, onUnlock: () -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.wallet_locked_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        BrassButton(
            label = stringResource(R.string.wallet_unlock_button),
            onClick = onUnlock,
            variant = BrassButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun UnlockedContent(
    modifier: Modifier,
    state: WalletUiState,
    onScan: () -> Unit,
    onPickGallery: () -> Unit,
    onReExport: (WalletDoc) -> Unit,
    onWhatProves: (WalletDoc) -> Unit,
    onDelete: (WalletDoc) -> Unit,
) {
    val spacing = LocalSpacing.current
    val now = remember { System.currentTimeMillis() }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = spacing.md),
    ) {
        item {
            Text(
                stringResource(R.string.wallet_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                BrassButton(
                    label = stringResource(R.string.wallet_add_scan),
                    onClick = onScan,
                    variant = BrassButtonVariant.Primary,
                    enabled = !state.isSaving,
                    modifier = Modifier.weight(1f),
                )
                BrassButton(
                    label = stringResource(R.string.wallet_add_gallery),
                    onClick = onPickGallery,
                    variant = BrassButtonVariant.Secondary,
                    enabled = !state.isSaving,
                    modifier = Modifier.weight(1f),
                )
            }
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
        if (state.docs.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wallet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.docs, key = { it.id }) { doc ->
                WalletDocRow(
                    doc = doc,
                    nowMs = now,
                    isExporting = state.isExporting,
                    onReExport = { onReExport(doc) },
                    onWhatProves = { onWhatProves(doc) },
                    onDelete = { onDelete(doc) },
                )
            }
        }
    }
}

@Composable
private fun WalletDocRow(
    doc: WalletDoc,
    nowMs: Long,
    isExporting: Boolean,
    onReExport: () -> Unit,
    onWhatProves: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(doc.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(walletTypeLabel(doc.type)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.wallet_captured_days_ago, doc.ageDays(nowMs).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.wallet_delete_cd, doc.title),
                    )
                }
            }
            if (doc.isStale(nowMs)) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(
                        stringResource(R.string.wallet_staleness_hint, doc.type.stalenessDays ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(spacing.sm),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                BrassButton(
                    label = stringResource(R.string.wallet_reexport),
                    onClick = onReExport,
                    variant = BrassButtonVariant.Secondary,
                    enabled = !isExporting,
                    modifier = Modifier.weight(1f),
                )
                BrassButton(
                    label = stringResource(R.string.wallet_what_proves),
                    onClick = onWhatProves,
                    variant = BrassButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SaveToWalletDialog(
    onSave: (WalletDocType, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var type by remember { mutableStateOf(WalletDocType.ID) }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wallet_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    stringResource(R.string.wallet_save_type_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                WalletDocType.values().forEach { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = type == t, onClick = { type = t }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(stringResource(walletTypeLabel(t)))
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.wallet_save_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.wallet_save_note_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(type, title, note) }) {
                Text(stringResource(R.string.wallet_save_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.wallet_save_cancel)) }
        },
    )
}

@Composable
private fun WhatDoesThisProveDialog(
    doc: WalletDoc,
    programs: List<`in`.jphe.storyvox.data.wallet.WalletProgramCatalog.AcceptingProgram>,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wallet_what_proves_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (programs.isEmpty()) {
                    Text(stringResource(R.string.wallet_what_proves_none))
                } else {
                    Text(stringResource(R.string.wallet_what_proves_intro, stringResource(walletTypeLabel(doc.type))))
                    programs.forEach { p ->
                        Text("• ${p.displayName}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        stringResource(R.string.wallet_what_proves_seed_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.wallet_ok)) }
        },
    )
}

private fun walletTypeLabel(type: WalletDocType): Int = when (type) {
    WalletDocType.ID -> R.string.wallet_type_id
    WalletDocType.PROOF_OF_ADDRESS -> R.string.wallet_type_address
    WalletDocType.PROOF_OF_INCOME -> R.string.wallet_type_income
    WalletDocType.AWARD_LETTER -> R.string.wallet_type_award
    WalletDocType.BENEFIT_CARD -> R.string.wallet_type_card
    WalletDocType.OTHER -> R.string.wallet_type_other
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun shareWalletPdf(context: Context, filePath: String, fileName: String, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, chooserTitle).also { it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },
    )
}
