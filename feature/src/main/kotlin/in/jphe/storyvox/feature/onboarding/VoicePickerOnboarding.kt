package `in`.jphe.storyvox.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.engine.VoicePickerGateViewModel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceManager
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.BrassProgressBar
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #599 + #600 + #627 — second of three first-launch welcome
 * screens. Replaces the engineering-jargon voice picker (engine names,
 * tier strings, raw model IDs) with a 5-year-old-friendly version:
 *
 *   - Friendly first names only (Brian, Amy, Lessac, Cori) — NO engine
 *     identifiers, NO quality-tier labels, NO sample-rate fluff.
 *   - One-line plain-English description per voice ("Warm American
 *     narrator", "British, slow and gentle", etc.).
 *   - Download size + "Free" tag visible — honest about what they're
 *     committing to but framed as a positive, not a tax.
 *   - One brass button per row labeled "Pick this voice" (NOT
 *     "Activate", which #627 flagged as ambiguous).
 *   - "Skip — I'll choose later" link at the bottom. When the user
 *     skips, no voice is downloaded; the VoicePickerGate stays armed
 *     and reprompts when the user taps a chapter.
 *
 * Reuses the existing [VoicePickerGateViewModel] to drive the download
 * progress flow — this screen is the welcome-flow front end; the gate
 * remains the source of truth for "is a voice active". When the user
 * picks a voice here, the gate's `pick()` runs the download +
 * `setActive` and (because activeVoice flips non-null) the gate
 * suppresses itself for the post-welcome experience.
 */
@Composable
fun VoicePickerOnboarding(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onMoreVoices: () -> Unit,
    viewModel: VoicePickerGateViewModel = hiltViewModel(),
) {
    val recommended by viewModel.recommended.collectAsStateWithLifecycle()
    val downloadingId by viewModel.downloadingVoiceId.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val activeVoice by viewModel.activeVoice.collectAsStateWithLifecycle()

    // Latch on the initial activeVoice — if the user already had a
    // voice picked (post-reset replay), we don't want
    // [onPickComplete] to fire on the first composition. The latch
    // captures the entry value once via [remember]; subsequent
    // re-emissions trigger the auto-advance only when the value
    // changes from the initial null state.
    val initialActive = remember { activeVoice }
    // Issue #682 — if the user already has an active voice when this
    // step composes (the #676 System TTS seed populated it before
    // onboarding began), skip the picker entirely. The user can
    // change voices via Settings → Voice library. Pre-fix the
    // download-only Piper picker rendered anyway, re-blocking the
    // exact zero-download path #676 was supposed to enable.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (initialActive != null) {
            onContinue()
        }
    }
    androidx.compose.runtime.LaunchedEffect(activeVoice, downloadingId) {
        // Auto-advance when a download successfully completes:
        //   - activeVoice transitions from initial (typically null)
        //     to non-null AND
        //   - the download isn't mid-flight anymore.
        if (activeVoice != null && activeVoice != initialActive && downloadingId == null) {
            onContinue()
        }
    }

    VoicePickerOnboardingContent(
        recommended = recommended,
        downloadingVoiceId = downloadingId,
        progress = progress,
        onPick = { voiceId ->
            viewModel.pick(voiceId)
        },
        onContinue = onContinue,
        onSkip = onSkip,
        onMoreVoices = onMoreVoices,
        onDismissProgress = viewModel::dismissProgress,
    )
}

@Composable
private fun VoicePickerOnboardingContent(
    recommended: List<UiVoiceInfo>,
    downloadingVoiceId: String?,
    progress: VoiceManager.DownloadProgress?,
    onPick: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onMoreVoices: () -> Unit,
    onDismissProgress: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(spacing.lg))
            Text(
                "Pick a voice",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                "Each voice is a small free download. " +
                    "You can change it any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 400.dp),
            )
            Spacer(Modifier.height(spacing.lg))

            // Show at most 3-4 friendly voices for the v1.0 onboarding.
            // The catalog's featuredIds today is 5 (Lessac × 3 tiers,
            // Cori × 2 tiers); we collapse same-named entries to their
            // medium tier so the user sees ONE row per *voice
            // personality*, not three rows of "Lessac" that look like
            // bugs. The full picker (More voices) keeps every tier
            // visible for power users.
            val friendlyVoices = remember(recommended) { friendlyVoiceSelection(recommended) }

            friendlyVoices.forEach { friendly ->
                FriendlyVoiceTile(
                    voice = friendly,
                    isDownloading = downloadingVoiceId == friendly.voice.id,
                    progress = progress.takeIf { downloadingVoiceId == friendly.voice.id },
                    enabled = downloadingVoiceId == null,
                    onPick = { onPick(friendly.voice.id) },
                    onDismissError = onDismissProgress,
                )
                Spacer(Modifier.height(spacing.sm))
            }

            Spacer(Modifier.height(spacing.md))
            BrassButton(
                label = "More voices →",
                onClick = onMoreVoices,
                variant = BrassButtonVariant.Text,
                enabled = downloadingVoiceId == null,
            )
            BrassButton(
                label = "Skip — I'll choose later",
                onClick = onSkip,
                variant = BrassButtonVariant.Text,
                enabled = downloadingVoiceId == null,
            )
            Spacer(Modifier.height(spacing.xl))
        }
    }
}

/**
 * One row in the friendly voice picker — a name, a one-line plain-
 * English description, a download size + Free tag, and a "Pick this
 * voice" button. While downloading, the button collapses into a
 * progress block (same shape as the legacy gate's downloader so the
 * visual progresses identically).
 *
 * Why a single big "Pick this voice" button instead of a row-wide tap
 * target: a 5-year-old (or a TalkBack user navigating with single-tap
 * gestures) shouldn't have to discover that the whole tile is
 * clickable. An explicit button is unambiguous — and per #627, the
 * button label is "Pick this voice", not the ambiguous "Activate".
 */
@Composable
private fun FriendlyVoiceTile(
    voice: FriendlyVoice,
    isDownloading: Boolean,
    progress: VoiceManager.DownloadProgress?,
    enabled: Boolean,
    onPick: () -> Unit,
    onDismissError: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sizeMb = (voice.voice.sizeBytes / 1_000_000L).coerceAtLeast(1L)
    val failed = progress as? VoiceManager.DownloadProgress.Failed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text(
                    voice.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    voice.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Free · ${sizeMb} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (isDownloading && progress != null) {
            Spacer(Modifier.height(spacing.sm))
            FriendlyDownloadProgress(
                voiceName = voice.displayName,
                progress = progress,
                onDismissError = onDismissError,
            )
        } else if (failed != null) {
            Spacer(Modifier.height(spacing.sm))
            FriendlyDownloadProgress(
                voiceName = voice.displayName,
                progress = failed,
                onDismissError = onDismissError,
            )
        } else {
            Spacer(Modifier.height(spacing.sm))
            BrassButton(
                label = "Pick this voice",
                onClick = onPick,
                variant = BrassButtonVariant.Primary,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun FriendlyDownloadProgress(
    voiceName: String,
    progress: VoiceManager.DownloadProgress,
    onDismissError: () -> Unit,
) {
    val spacing = LocalSpacing.current
    when (progress) {
        VoiceManager.DownloadProgress.Resolving -> {
            Text(
                "Getting $voiceName ready…",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassProgressBar(progress = null, modifier = Modifier.fillMaxWidth())
        }
        is VoiceManager.DownloadProgress.Downloading -> {
            val pct = if (progress.totalBytes > 0L) {
                (progress.bytesRead.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
            } else 0f
            val mb = progress.bytesRead / 1_000_000
            val totalMb = progress.totalBytes / 1_000_000
            Text(
                "Downloading $voiceName… $mb MB / $totalMb MB",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassProgressBar(
                progress = if (progress.totalBytes > 0L) pct else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        VoiceManager.DownloadProgress.Done -> {
            Text(
                "$voiceName is ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is VoiceManager.DownloadProgress.Failed -> {
            Text(
                "Couldn't download $voiceName. " + progress.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassButton(
                label = "Try again",
                onClick = onDismissError,
                variant = BrassButtonVariant.Text,
            )
        }
    }
}

/**
 * Friendly-name overlay for a single catalog voice — pairs the
 * engineering-named [UiVoiceInfo] with a plain-English [displayName]
 * and [description] that a 5-year-old reader (or a TalkBack listener)
 * can parse on first read.
 */
internal data class FriendlyVoice(
    val voice: UiVoiceInfo,
    val displayName: String,
    val description: String,
)

/**
 * Curate the recommended-voice list down to 3-4 friendly entries by
 * collapsing same-personality tiers (Lessac low/medium/high becomes a
 * single "Lessac" entry pointing at the medium tier) and layering a
 * plain-English description over each. The catalog's existing display
 * names ("Lessac", "Cori") are kept verbatim — they're already first
 * names — but each is paired with a one-line description so a brand-
 * new user knows what "Lessac" *sounds like* without having to tap
 * "Hear a sample".
 *
 * Why we don't dynamically detect gender/accent from the catalog and
 * synthesise the description: the catalog's per-voice metadata is
 * inconsistent (some en_GB Piper voices are unlabeled, Kokoro's
 * speaker-index voices have no per-row gender at all). A hand-curated
 * lookup table is one PR-to-update vs. a metadata audit across the
 * whole catalog.
 */
internal fun friendlyVoiceSelection(recommended: List<UiVoiceInfo>): List<FriendlyVoice> {
    if (recommended.isEmpty()) return emptyList()
    val descriptions = mapOf(
        "lessac" to "Warm American narrator — clear and steady.",
        "cori" to "British, gentle and even — good for long sessions.",
        "amy" to "Friendly American narrator — easy to follow.",
        "ryan" to "American narrator — calm and slow.",
        "alan" to "British narrator — warm and grounded.",
        "alba" to "British, soft-spoken — quiet hours and bedtime.",
    )
    // Collapse same-named entries to the FIRST one we see (catalog
    // orders featuredIds by tier: low → medium → high). Picking the
    // first means we surface the smallest download by default — a
    // user with patchy wifi gets to listen sooner. Power users hit
    // "More voices →" for the high-tier variants.
    val seen = LinkedHashSet<String>()
    val collapsed = recommended.filter { v ->
        val key = v.displayName.lowercase()
        if (key in seen) false else { seen.add(key); true }
    }
    return collapsed.take(4).map { v ->
        FriendlyVoice(
            voice = v,
            displayName = v.displayName,
            description = descriptions[v.displayName.lowercase()]
                ?: "${v.displayName} — small free download.",
        )
    }
}
