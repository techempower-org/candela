package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.settings.components.StatusPill
import `in`.jphe.storyvox.feature.settings.components.StatusTone
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Bookshare subscreen (#1471).
 *
 * A single masked field to enter / clear the Bookshare partner
 * `api_key`. The key is write-only from the UI's perspective — it's
 * persisted encrypted (`storyvox.secrets`) and never read back into the
 * field; the status pill reflects only whether one is configured
 * (mirrors the AI BYOK-key posture, so the secret never round-trips
 * through UI state).
 *
 * With a key stored, Bookshare's discovery surface (search / browse /
 * categories) goes live. Content download stays gated on the Bookshare
 * partner agreement (a later stage), independent of this key.
 */
@Composable
fun BookshareSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(
        title = stringResource(R.string.settings_bookshare_title),
        onBack = onBack,
    ) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                BookshareApiKeySection(
                    keyConfigured = s.bookshareKeyConfigured,
                    onSaveKey = viewModel::setBookshareApiKey,
                    onClearKey = { viewModel.setBookshareApiKey(null) },
                )
            }
        }
    }
}

@Composable
private fun BookshareApiKeySection(
    keyConfigured: Boolean,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
) {
    val spacing = LocalSpacing.current

    val (statusText, statusTone) = if (keyConfigured) {
        stringResource(R.string.settings_bookshare_status_configured) to StatusTone.Connected
    } else {
        stringResource(R.string.settings_bookshare_status_unconfigured) to StatusTone.Neutral
    }
    StatusPill(text = statusText, tone = statusTone)

    Column(
        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // Write-only field: the stored key is a partner secret and is
        // never surfaced back into the UI. Typing a new value + Save
        // replaces it; the status pill above reports configured state.
        var keyInput by remember { mutableStateOf("") }
        var keyVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(stringResource(R.string.settings_bookshare_key_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.settings_bookshare_help))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            BrassButton(
                label = stringResource(R.string.settings_bookshare_save),
                onClick = {
                    onSaveKey(keyInput.trim())
                    keyInput = ""
                },
                variant = BrassButtonVariant.Primary,
                enabled = keyInput.isNotBlank(),
            )
            if (keyConfigured) {
                BrassButton(
                    label = stringResource(R.string.settings_bookshare_clear),
                    onClick = onClearKey,
                    variant = BrassButtonVariant.Secondary,
                )
            }
        }
    }
}
