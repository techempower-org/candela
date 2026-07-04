package `in`.jphe.storyvox.feature.docs.profile

import android.app.Activity
import android.app.KeyguardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #1519 — saved household profile screen.
 *
 * An optional, on-device profile (name / address / household size / income
 * / phone / email) that autofills scanned benefits forms. Edit + delete
 * are gated behind the device's own lock (biometric or PIN/pattern) via
 * [KeyguardManager]; the data is encrypted at rest and excluded from
 * backup/sync. **SSN is never a field here** — it's type-to-fill only.
 *
 * Why device-credential rather than androidx BiometricPrompt: the host
 * `MainActivity` is a `ComponentActivity` (BiometricPrompt needs a
 * `FragmentActivity`), and the wallet lane (#1514) owns the shared
 * BiometricPrompt seam. `createConfirmDeviceCredentialIntent` gives the
 * same "prove it's you (fingerprint / face / PIN)" gate with no new
 * dependency. TODO(#1514): swap to the wallet's BiometricPrompt gate when
 * it lands.
 *
 * Accessibility (invariant #4): labelled fields, live-region save
 * confirmation, plain top-to-bottom form (no spatial UI).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdProfileScreen(
    onNavigateBack: () -> Unit,
    viewModel: HouseholdProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) pendingAction?.invoke()
        pendingAction = null
    }

    val authTitle = stringResource(R.string.profile_auth_title)
    val authDesc = stringResource(R.string.profile_auth_desc)

    // Run [action] only after the user proves it's them. If the device has
    // no lock set we can't prompt — proceed (the data is still app-private
    // + encrypted at rest).
    fun gate(action: () -> Unit) {
        val km = keyguard
        if (km != null && km.isDeviceSecure) {
            pendingAction = action
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(authTitle, authDesc)
            if (intent != null) authLauncher.launch(intent) else { pendingAction = null; action() }
        } else {
            action()
        }
    }

    val savedMsg = stringResource(R.string.profile_saved_announce)
    LaunchedEffect(state.justSaved) {
        if (state.justSaved) {
            Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
            viewModel.consumeJustSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.profile_back_cd),
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
            Text(
                stringResource(R.string.profile_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val locked = state.hasSavedProfile && !unlocked
            if (locked) {
                LockedCard(
                    onUnlock = { gate { unlocked = true } },
                    onDelete = { gate { showDeleteConfirm = true } },
                )
            } else {
                ProfileForm(
                    draft = state.draft,
                    onFieldChange = viewModel::onFieldChange,
                )

                SsnPolicyNote()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    if (state.hasUnsavedChanges) {
                        BrassButton(
                            label = stringResource(R.string.profile_revert),
                            onClick = viewModel::revert,
                            variant = BrassButtonVariant.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    BrassButton(
                        label = stringResource(R.string.profile_save),
                        onClick = viewModel::save,
                        variant = BrassButtonVariant.Primary,
                        enabled = state.hasUnsavedChanges,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (state.hasSavedProfile) {
                    BrassButton(
                        label = stringResource(R.string.profile_delete),
                        onClick = { gate { showDeleteConfirm = true } },
                        variant = BrassButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AutofillPreview(profile = state.saved)
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.profile_delete_confirm_title)) },
            text = { Text(stringResource(R.string.profile_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile()
                    unlocked = false
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.profile_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            },
        )
    }
}

@Composable
private fun LockedCard(onUnlock: () -> Unit, onDelete: () -> Unit) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.profile_locked),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = spacing.sm),
                )
            }
            BrassButton(
                label = stringResource(R.string.profile_unlock),
                onClick = onUnlock,
                variant = BrassButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            BrassButton(
                label = stringResource(R.string.profile_delete),
                onClick = onDelete,
                variant = BrassButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProfileForm(
    draft: HouseholdProfile,
    onFieldChange: (ProfileField, String) -> Unit,
) {
    FIELD_SPECS.forEach { spec ->
        OutlinedTextField(
            value = draft.valueFor(spec.field),
            onValueChange = { onFieldChange(spec.field, it) },
            label = { Text(stringResource(spec.labelRes)) },
            singleLine = spec.field != ProfileField.ADDRESS,
            keyboardOptions = KeyboardOptions(keyboardType = spec.keyboard),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SsnPolicyNote() {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.profile_ssn_policy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(spacing.md),
        )
    }
}

/**
 * Live demonstration of how the saved profile autofills a scanned form —
 * the same [ProfileAutofillChip] the fillable-PDF screen (#1512) will use,
 * plus an SSN example proving it warns instead of suggesting.
 */
@Composable
private fun AutofillPreview(profile: HouseholdProfile) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    Text(
        stringResource(R.string.profile_autofill_preview_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        stringResource(R.string.profile_autofill_preview_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        FIELD_SPECS.forEach { spec ->
            if (profile.valueFor(spec.field).isNotBlank()) {
                val sampleLabel = stringResource(spec.labelRes)
                ProfileAutofillChip(
                    detectedLabel = sampleLabel,
                    profile = profile,
                    onFill = { value ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.profile_preview_filled, value),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
        // Always show the SSN guard so it's visible in the demo.
        SensitiveFieldNote()
    }
}

private data class FieldSpec(val field: ProfileField, val labelRes: Int, val keyboard: KeyboardType)

private val FIELD_SPECS = listOf(
    FieldSpec(ProfileField.FULL_NAME, R.string.profile_field_name, KeyboardType.Text),
    FieldSpec(ProfileField.ADDRESS, R.string.profile_field_address, KeyboardType.Text),
    FieldSpec(ProfileField.HOUSEHOLD_SIZE, R.string.profile_field_household_size, KeyboardType.Number),
    FieldSpec(ProfileField.MONTHLY_INCOME, R.string.profile_field_income, KeyboardType.Number),
    FieldSpec(ProfileField.PHONE, R.string.profile_field_phone, KeyboardType.Phone),
    FieldSpec(ProfileField.EMAIL, R.string.profile_field_email, KeyboardType.Email),
)
