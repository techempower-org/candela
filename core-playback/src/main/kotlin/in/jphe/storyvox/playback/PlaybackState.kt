package `in`.jphe.storyvox.playback

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackState(
    val currentFictionId: String? = null,
    val currentChapterId: String? = null,
    val charOffset: Int = 0,
    val durationEstimateMs: Long = 0L,
    val isPlaying: Boolean = false,
    /** True when the AudioTrack is paused waiting for the producer to
     *  refill the queue past the underrun threshold. UI surfaces a
     *  "Buffering..." spinner; differs from `!isPlaying` (user pause)
     *  and from `isPlaying && sentenceEnd == 0` (initial voice warm-up). */
    val isBuffering: Boolean = false,
    val currentSentenceRange: SentenceRange? = null,
    val voiceId: String? = null,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val sleepTimerRemainingMs: Long? = null,
    /** Issue #150 — when true (default), a shake gesture during the
     *  sleep timer's fade tail re-arms the timer. Surfaces in
     *  Settings → Reading so users with bumpy commutes can opt out.
     *  Driven from SettingsRepositoryUi via AppBindings. */
    val shakeToExtendEnabled: Boolean = true,
    val bookTitle: String? = null,
    val chapterTitle: String? = null,
    val coverUri: String? = null,
    val error: PlaybackError? = null,
    /**
     * Issue #373 — true when the currently-loaded chapter is a Media3-
     * routed audio stream (KVMR live radio, future LibriVox MP3, etc.)
     * rather than text-for-TTS. The pitch slider hides on this — Sonic
     * pitch-shifting applies to PCM the engine renders; for a live
     * stream we're handing bytes to ExoPlayer and there's no PCM to
     * shift. UI surfaces (AudiobookView pitch slider, Settings → Voice
     * & Playback pitch slider) read this flag through PlaybackController.
     */
    val isLiveAudioChapter: Boolean = false,
    /** Issue #1308 — teleprompter state mirrored from `TeleprompterController`
     *  so the Wear remote can reflect it. Populated by `PhoneWearBridge` at
     *  publish time; the controller stays the source of truth. Defaults keep
     *  older watch builds + the non-teleprompter sync path unaffected. */
    val teleprompterEnabled: Boolean = false,
    val teleprompterPlaying: Boolean = false,
    val teleprompterWpm: Int = 0,
)

@Serializable
data class SentenceRange(
    val sentenceIndex: Int,
    val startCharInChapter: Int,
    val endCharInChapter: Int,
)

@Serializable
sealed class PlaybackError {
    @Serializable data object EngineUnavailable : PlaybackError()
    @Serializable data class ChapterFetchFailed(val message: String) : PlaybackError()
    @Serializable data class TtsSpeakFailed(val utteranceId: String, val errorCode: Int) : PlaybackError()

    // Azure HD voices (#184, PR-5) — typed surface for the four
    // distinct failure modes Azure synthesis can hit. Each one drives
    // different UX:
    //  - AzureAuthFailed → stop pipeline, prompt re-paste key.
    //  - AzureThrottled → user has hit Azure F0 quota; advise local
    //    voice or wait for monthly reset.
    //  - AzureNetworkUnavailable → connectivity gone; PR-6 wires a
    //    fallback voice toggle to keep playback going on a local
    //    voice instead.
    //  - AzureServerError → Azure-side outage; retry budget exhausted.

    /** 401 / 403 — bad / revoked subscription key. The producer
     *  stops the pipeline on this error because every subsequent
     *  sentence will fail the same way. The Settings → Cloud voices
     *  surface picks up the key-rejected state via the same shared
     *  AzureCredentials flow. */
    @Serializable data object AzureAuthFailed : PlaybackError()

    /** 429 — Azure rejected the request after the client's internal
     *  backoff retries (250ms / 500ms / 1s). Likely the F0 quota
     *  (500K chars/month for HD) is exhausted. Surfaces as a one-shot
     *  toast/banner; the user can switch to a local voice. */
    @Serializable data class AzureThrottled(val message: String) : PlaybackError()

    /** TCP / TLS / DNS failure. PR-6 wraps the Azure handle with a
     *  fallback voice when the user has the toggle on; without it,
     *  this is a hard error and the playback sheet shows
     *  "Network required for cloud voices." */
    @Serializable data class AzureNetworkUnavailable(val message: String) : PlaybackError()

    /** 5xx (after retries) — Azure-side outage. Same UX as
     *  [AzureNetworkUnavailable] today; could be surfaced with a
     *  different copy ("Azure servers having issues") if JP wants to
     *  blame Microsoft explicitly. */
    @Serializable data class AzureServerError(val httpCode: Int, val message: String) : PlaybackError()
}

sealed class SleepTimerMode {
    data class Duration(val minutes: Int) : SleepTimerMode()
    data object EndOfChapter : SleepTimerMode()
}

sealed class PlaybackUiEvent {
    data object BookFinished : PlaybackUiEvent()
    data class ChapterChanged(val chapterId: String) : PlaybackUiEvent()
    /**
     * Calliope (v0.5.00) — a chapter just finished *naturally* (the
     * pipeline reached end-of-text + the AudioTrack drained). Fires
     * once per natural completion; explicit user nav (Next chapter
     * button, jumpToChapter) does NOT fire this. Used by the v0.5.00
     * milestone confetti easter-egg to mark "the user actually listened
     * to a chapter on the new build" — gated separately in the UI on
     * the one-time `KEY_V0500_CONFETTI_SHOWN` DataStore flag.
     *
     * Emitted from [in.jphe.storyvox.playback.tts.EnginePlayer.handleChapterDone]
     * *before* the auto-advance fires [ChapterChanged], so a UI that
     * observes both events sees ChapterDone → ChapterChanged in that
     * order on natural completion, and only ChapterChanged on user nav.
     */
    data class ChapterDone(val chapterId: String) : PlaybackUiEvent()
    data class EngineMissing(val installUrl: String) : PlaybackUiEvent()
    /**
     * PR-6 (#185) — Azure synth failed (non-auth, non-stop) with the
     * offline-fallback toggle on; storyvox auto-swapped to the user's
     * chosen local voice. The playback sheet renders this as a
     * one-shot toast: "Azure offline — using [fallback voice]". Fires
     * **once per chapter** (the consumer dedupes via "did we already
     * emit this for sentenceIndex 0–N? → suppress"); auto-clearing on
     * chapter change is fine because the user already learned the
     * fallback is active.
     */
    data class AzureFellBack(val fallbackVoiceLabel: String) : PlaybackUiEvent()
}

fun PlaybackState.scrubProgress(): Float {
    // Issue #555 — duration lives on the media-time (speed-1) axis so
    // the rail and the position both speak the same speed-invariant
    // language. Total chars on the rail = duration / baseline (no
    // `* speed`); progress = charOffset / totalChars. The result is the
    // text-consumption fraction, stable across speed changes — exactly
    // what the scrubber thumb's pixel position should track.
    if (durationEstimateMs <= 0L) return 0f
    val totalChars = (durationEstimateMs.toFloat() / 1000f) *
        SPEED_BASELINE_CHARS_PER_SECOND
    if (totalChars <= 0f) return 0f
    return (charOffset / totalChars).coerceIn(0f, 1f)
}

const val SPEED_BASELINE_WPM = 150f
const val SPEED_BASELINE_CHARS_PER_SECOND = SPEED_BASELINE_WPM * 5f / 60f
