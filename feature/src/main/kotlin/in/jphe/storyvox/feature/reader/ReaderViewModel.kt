package `in`.jphe.storyvox.feature.reader

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.db.entity.Annotation
import `in`.jphe.storyvox.data.dictionary.DictionaryRepository
import `in`.jphe.storyvox.data.dictionary.DictionaryResult
import `in`.jphe.storyvox.data.dictionary.WordDefinition
import `in`.jphe.storyvox.data.dictionary.normalizeLookupWord
import `in`.jphe.storyvox.data.repository.AnnotationRepository
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.data.repository.DEFAULT_BOOK_SEARCH_LIMIT
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackResumePolicyConfig
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.HighlightMode
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.ui.theme.ReaderTypography
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.ui.theme.ReaderColors
import `in`.jphe.storyvox.playback.PlaybackUiEvent
import `in`.jphe.storyvox.playback.PendingTeleprompterScript
import `in`.jphe.storyvox.playback.TeleprompterController
import `in`.jphe.storyvox.playback.TeleprompterScriptStore
import `in`.jphe.storyvox.playback.transcribe.AsrModelProvider
import `in`.jphe.storyvox.playback.transcribe.VoicePacedScrollController
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.feature.ChapterRecap
import `in`.jphe.storyvox.ui.component.ReaderView
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ReaderUiState(
    val playback: UiPlaybackState? = null,
    val chapterText: String = "",
    val activePane: ReaderView = ReaderView.Audiobook,
    /** Issue #278 — how long the player has been stuck in the "no chapter
     *  title + no chapter text" loading state. Drives the soft slow hint
     *  + the hard timeout/retry error path in [AudiobookView]. Reset to
     *  [LoadingPhase.NotLoading] as soon as either piece of state arrives. */
    val loadingPhase: LoadingPhase = LoadingPhase.NotLoading,
    /** Issue #418 — the magical-voice-icon quick sheet shows the live
     *  inter-sentence pause multiplier (#109) alongside speed/pitch so
     *  the user can tune cadence without leaving the player. The value is
     *  the global default from [SettingsRepositoryUi]; the engine reads
     *  it via [PlaybackControllerUi.setPunctuationPauseMultiplier]
     *  (effect lands on the next sentence boundary). */
    val punctuationPauseMultiplier: Float = 1f,
    /** Issue #418 — the magical-voice-icon quick sheet exposes the
     *  high-quality Sonic pitch-interpolation toggle (#193) so users
     *  on slow hardware can flip it off without diving into Settings.
     *  Default true matches the Settings default. */
    val pitchInterpolationHighQuality: Boolean = true,
    /**
     * "Why are we waiting?" — non-null whenever audio is NOT reaching
     * the speakers (warming, buffering, focus lost, device muted, route
     * change, stuck). The reader UI renders the brass diagnostic panel
     * above the cover based on this value. Sourced from
     * [PlaybackControllerUi.waitReason] which forwards
     * AudioOutputMonitor's flow.
     */
    val waitReason: `in`.jphe.storyvox.playback.diagnostics.WaitReason? = null,
    /**
     * Issue #677 — end-of-book latch. Set true when the engine emits
     * [PlaybackUiEvent.BookFinished] (the last chapter ended naturally
     * with no successor). Drives [HybridReaderScreen]'s
     * `BookFinishedOverlay` — pre-fix, the screen rendered nothing
     * after the final sentence drained and the scrubber sat stuck at
     * the end. Cleared via [ReaderViewModel.acknowledgeBookFinished]
     * (overlay dismissal / Back to Library / Browse more) or by a
     * fresh [ReaderViewModel.resume] / `startListening` call so a
     * "Restart from beginning" doesn't immediately re-trip the modal.
     */
    val bookFinished: Boolean = false,
    /**
     * Issue #805 — typed playback error from [EngineState.Error]. Non-null
     * when the engine is in an error state; the reader surfaces a
     * dismissible banner with an error-type-specific message and
     * recovery action (retry, sign-in, etc.). Null when no error.
     */
    val playbackError: `in`.jphe.storyvox.playback.EngineState.Error? = null,
)

/** Issue #278 — the player loading screen used to be a silent eternity:
 *  if the chapter fetch or voice-model load hung, the user stared at
 *  "Conjuring your chapter…" forever with no retry, no error, no escape.
 *
 *  This sealed surface tracks progress through the loading window so
 *  [AudiobookView] can layer in:
 *   - a soft "Still working… (slow voice / network)" hint at 10s, and
 *   - a hard error block with Retry / Pick a different voice / Cancel
 *     at 30s.
 *
 *  Once chapter text or a chapter title arrives, the phase snaps back to
 *  [NotLoading] regardless of where we were on the timer. */
enum class LoadingPhase {
    /** Player has chapter title / chapter text — not loading. */
    NotLoading,
    /** First 10s of a fresh load — show the regular sigil + copy. */
    Loading,
    /** 10-30s into a load — show a "Still working…" secondary line so
     *  the user knows we haven't deadlocked. */
    Slow,
    /** 30s+ — surface a friendly error block with Retry / Pick voice /
     *  Cancel. The loading flow itself isn't cancelled; we just give the
     *  user a way out. */
    TimedOut,
}

/** UI state for the Chapter Recap modal. Sealed because the
 *  states are mutually exclusive — at any given moment the modal is
 *  either closed, asking, streaming, done, or in error. */
@Immutable
sealed class RecapUiState {
    /** Modal not visible. */
    object Hidden : RecapUiState()

    /** Modal opened, waiting for the first token from the LLM. */
    object Loading : RecapUiState()

    /** First token arrived; partial response builds up. */
    data class Streaming(val text: String) : RecapUiState()

    /** Stream completed successfully. */
    data class Done(val text: String) : RecapUiState()

    /** Stream failed. The UI shows [message] + the appropriate
     *  recovery action (Settings link, Try again, etc.). */
    data class Error(
        val message: String,
        val kind: ErrorKind,
    ) : RecapUiState()

    enum class ErrorKind {
        /** AI not configured, or "Send chapter text to AI" toggle off
         *  → route to Settings. */
        NotConfigured,
        /** Provider key invalid → route to Settings, flag the field. */
        AuthFailed,
        /** Network/transport — recoverable. */
        Transport,
        /** Provider returned a non-auth 4xx/5xx. */
        ProviderError,
    }
}

/**
 * Issue #1229 — observable state for the reader's whole-book search overlay.
 * [HybridReaderScreen] collects this; the overlay renders the field
 * ([query]), the [results] list (one row per matching chapter), a stepper
 * over them ([selectedIndex]), and a [phase]-driven empty/searching/no-match
 * surface. [truncated] is set when the chapter cap was hit so the overlay can
 * tell the user not every chapter was searched.
 */
@Immutable
data class BookSearchUiState(
    val open: Boolean = false,
    val query: String = "",
    val results: List<BookSearchResult> = emptyList(),
    val selectedIndex: Int = 0,
    val phase: BookSearchPhase = BookSearchPhase.Idle,
    val truncated: Boolean = false,
)

/** Issue #1229 — lifecycle of a single book-search query. */
enum class BookSearchPhase {
    /** No query yet (or below the minimum length) — show the prompt copy. */
    Idle,
    /** Query is in flight against the DB / search core. */
    Searching,
    /** Search finished — [BookSearchUiState.results] is authoritative (may be empty). */
    Done,
}

/** Issue #1229 — debounce before a keystroke triggers a DB search. */
private const val BOOK_SEARCH_DEBOUNCE_MS = 250L

/** Issue #1229 — minimum query length before searching, so a single common
 *  letter doesn't pull every chapter body into memory. */
private const val MIN_BOOK_SEARCH_QUERY = 2

/** Issue #1229 — internal carrier for the debounced results pipeline, merged
 *  with the open/query/selection flows into the public [BookSearchUiState]. */
private data class BookSearchResultsState(
    val phase: BookSearchPhase = BookSearchPhase.Idle,
    val results: List<BookSearchResult> = emptyList(),
    val truncated: Boolean = false,
)

/**
 * Issue #1230 — observable state for the reader's tap-to-define popup.
 * [ReaderViewModel.defineWord] drives the transition
 * [Hidden] → [Loading] → [Loaded] / [Empty] / [Error]; [HybridReaderScreen]
 * collects it and [ReaderTextView] renders the bottom sheet accordingly.
 * [Empty] and [Error] both still offer the system-dictionary + Ask-AI
 * fallbacks, so a missing word or a dropped network is never a dead end.
 */
sealed interface DictionaryUiState {
    /** No lookup in flight — the sheet is closed (resting state). */
    data object Hidden : DictionaryUiState

    /** A lookup for [word] is in flight; the sheet shows a spinner. */
    data class Loading(val word: String) : DictionaryUiState

    /** [definition] resolved — the sheet renders the part-of-speech entries. */
    data class Loaded(val definition: WordDefinition) : DictionaryUiState

    /** [word] is valid but has no dictionary entry (404 / empty body). */
    data class Empty(val word: String) : DictionaryUiState

    /** The lookup failed for [word]; [message] is the reason for the Retry row. */
    data class Error(val word: String, val message: String) : DictionaryUiState
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val playback: PlaybackControllerUi,
    private val settings: SettingsRepositoryUi,
    private val chapterRecap: ChapterRecap,
    /**
     * Source for the Playing-tab Resume prompt (#? Lyra). Same flow
     * LibraryViewModel reads for its "Continue listening" tile — keeps
     * the two surfaces visually in lock-step (same fiction, same chapter,
     * same updatedAt).
     */
    private val positionRepo: PlaybackPositionRepository,
    /**
     * #90 — the smart-resume policy. When the user explicitly paused
     * before, the Resume CTA should load-but-not-auto-play. When the app
     * was killed mid-playback (no explicit pause), the flag stays true
     * and Resume auto-plays. Identical wiring to
     * [`in`.jphe.storyvox.feature.library.LibraryViewModel.resume].
     */
    private val resumePolicy: PlaybackResumePolicyConfig,
    private val fictionRepo: FictionRepositoryUi,
    /**
     * Issue #999 phase 2 — read/write surface for in-reader text highlights.
     * The reader observes the loaded chapter's highlights ([chapterHighlights])
     * to render the spans, and writes via [createHighlight] / [updateHighlight]
     * / [deleteHighlight]. Injected as the core-data [AnnotationRepository]
     * directly — the same seam [FictionDetailViewModel] already uses for the
     * "Highlights & notes" list, so phase 2 doesn't add a parallel `*Ui` port.
     */
    private val annotationRepo: AnnotationRepository,
    /**
     * Issue #1229 — in-book text search. The reader runs a whole-book
     * find through [ChapterRepository.searchChapterBodies] (a `LIKE`
     * pre-filter over the fiction's downloaded chapters) and refines the
     * hits with the pure [searchBook] core. Injected as the core-data
     * repository directly — the same seam the playback layer + FictionDetail
     * already use — so book-search doesn't add a parallel `*Ui` port.
     */
    private val chapterRepo: ChapterRepository,
    /**
     * Issue #1230 — tap-to-define dictionary lookups for the reader's
     * long-press popup. The core-data interface is bound to the okhttp-backed
     * Wiktionary implementation in `:app`; the ViewModel only maps its
     * [DictionaryResult] onto [DictionaryUiState]. Injected as the core-data
     * repository directly, mirroring [annotationRepo] / [chapterRepo] — no
     * parallel `*Ui` port.
     */
    private val dictionaryRepo: DictionaryRepository,
    /**
     * Issue #1308 — shared teleprompter control state (enabled / playing / wpm),
     * hoisted out of `ReaderView`'s local Compose state into a `@Singleton` in
     * core-playback so the reader and the Wear remote (PR2) drive one source of
     * truth. Mode + practice-flow state intentionally stay local to the reader.
     */
    private val teleprompter: TeleprompterController,
    /**
     * Issue #1368 — voice-paced teleprompter. [voicePaced] bridges the live
     * mic→STT word stream into the forced aligner and publishes the speaker's
     * char offset; [asrModel] owns the downloadable on-device STT model. Both
     * are @Singletons in core-playback (the reader is surface-agnostic about
     * the recognizer); the reader maps the offset to a scroll position.
     */
    private val voicePaced: VoicePacedScrollController,
    private val asrModel: AsrModelProvider,
    /**
     * Issue #1369 — the cross-scope hand-off seam for "Load into Teleprompter".
     * When a [PendingTeleprompterScript] is parked here (by the script manager
     * or the AI writer, #1366), the reader scrolls that script's body instead
     * of the chapter body — see the [uiState] `chapterText` substitution and
     * [resetTeleprompter]/[setTeleprompterEnabled] which clear it.
     */
    private val teleprompterScriptStore: TeleprompterScriptStore,
    savedState: SavedStateHandle,
) : ViewModel() {

    /**
     * Issue #638 (v1.0 blocker) — distinguishes the "Reader screen
     * cold-launched from FictionDetail with explicit chapter args"
     * path from the "Playing tab cold-launch with no recent activity"
     * path. Both share [HybridReaderScreen], but only the second
     * should fall back to [ResumeEmptyPrompt] when `playback == null`.
     *
     * The Reader route is `/reader/{fictionId}/{chapterId}` — when
     * navigated to from FictionDetail's Play button, both args land
     * in SavedStateHandle, [hasExplicitChapterArgs] is true, and the
     * screen renders a loading state until the playback flow flips
     * non-null (which happens after `startListening` finishes the
     * chapter-download wait inside the controller). The Playing
     * route is `/playing` — no args, [hasExplicitChapterArgs] is
     * false, and the empty Resume prompt fires as before.
     *
     * Pre-fix the Reader screen unconditionally fired the empty
     * prompt on `playback == null`, which is the state-flow's
     * initial emission. The user tapped Play on FictionDetail, was
     * routed to /reader, the playback flow hadn't emitted yet, and
     * the bottom-dock-anchored "Your library awaits / Browse the
     * realms" empty state appeared, defeating PR #633's
     * discoverability fix. See feature/.../HybridReaderScreen.kt
     * for how this flag gates the prompt branch.
     */
    val hasExplicitChapterArgs: Boolean = run {
        val f = savedState.get<String>("fictionId")
        val c = savedState.get<String>("chapterId")
        !f.isNullOrBlank() && !c.isNullOrBlank()
    }

    /**
     * TechEmpower wrong-book regression (v1.1.1) — the fictionId from the
     * explicit `/reader/{fictionId}/{chapterId}` nav args, or null on the
     * bare `/playing` route. [HybridReaderScreen] compares this to the
     * GLOBAL PlaybackController's current fictionId: when they differ, the
     * controller is still showing a PRIOR (surviving) book and the screen
     * must hold the loading card for the requested fiction rather than
     * paint the prior book. See [readerContentMode]. Blank on the Playing
     * tab, which intentionally renders the controller's current chapter /
     * the resume prompt.
     */
    val argFictionId: String? = savedState.get<String>("fictionId")?.takeIf { it.isNotBlank() }

    private val _activePane = MutableStateFlow(ReaderView.Audiobook)
    private val _recap = MutableStateFlow<RecapUiState>(RecapUiState.Hidden)

    /** Recap modal state. Reader UI collects this and renders the
     *  modal when not [RecapUiState.Hidden]. */
    val recap: StateFlow<RecapUiState> = _recap.asStateFlow()

    /** Issue #189 — recap-aloud TTS pipeline state, surfaced from the
     *  PlaybackController so the modal's Read-aloud button can render the
     *  right play/pause icon. The chapter-recap modal collects this
     *  alongside [recap] (the modal-content state) — they're independent
     *  axes: the modal can be Done while the audio is Idle (button shows
     *  Play), or Done while Speaking (button shows Pause). */
    val recapPlayback: StateFlow<UiRecapPlaybackState> = playback.recapPlayback
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiRecapPlaybackState.Idle)

    /** The currently in-flight recap stream, or null when no recap is
     *  running. Cancelling this Job cancels the underlying OkHttp
     *  Call (TCP RST to the provider; they stop generating; we stop
     *  billing). */
    private var recapJob: Job? = null

    /** Issue #278 — derive a `isLoading` boolean directly from the
     *  playback state so we can drive a timer off it. "Loading" matches
     *  the same condition AudiobookView already uses for the brass
     *  spinner: no chapter title yet AND no chapter text yet. As soon as
     *  either arrives the flow flips false and the timer below resets. */
    private val isLoading: StateFlow<Boolean> = combine(
        playback.state,
        playback.chapterText,
    ) { state, text ->
        state.chapterTitle.isBlank() && text.isBlank()
    }.distinctUntilChanged().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), true,
    )

    /** Issue #278 — phase tracker. When the loading edge flips true we
     *  fire a coroutine that walks Loading → Slow (10s) → TimedOut (30s).
     *  When it flips false we cancel the timer and reset to NotLoading.
     *
     *  The thresholds match the values called out in the issue body:
     *  10s for the soft slow hint, 30s for the hard error path. They're
     *  intentionally non-private so the unit test can pin them. */
    private val _loadingPhase = MutableStateFlow(LoadingPhase.Loading)
    private var loadingTimerJob: Job? = null

    /**
     * Calliope (v0.5.00) — one-shot signal for the chapter-complete
     * confetti easter-egg. Conflated capacity 1 so a missed observer
     * (e.g. mid-config-change) sees the latest signal on next collect
     * but can't accumulate a backlog of celebrations. The collector
     * in [HybridReaderScreen] mounts a [MilestoneConfetti] overlay
     * for each Unit it pulls off the channel.
     *
     * Fires when ALL of these are true on a [PlaybackUiEvent.ChapterDone]:
     *  - build qualifies (VERSION_NAME ≥ 0.5.00),
     *  - the confetti flag is still false (one-time gate),
     *  - the event is a natural ChapterDone, not a ChapterChanged
     *    (the engine emits ChapterDone before ChapterChanged on the
     *    auto-advance path; manual nav skips ChapterDone).
     *
     * The flag flip-to-true happens from the UI side via
     * `markMilestoneConfettiShown()` when the overlay finishes
     * fading — keeps the side-effect path serialized through the
     * SettingsRepositoryUi seam rather than racing on a VM-local
     * boolean.
     */
    private val _confettiTrigger = Channel<Unit>(capacity = Channel.CONFLATED)
    val confettiTrigger: Flow<Unit> = _confettiTrigger.receiveAsFlow()

    /**
     * Candela (v1.1.0) — book-completion streak celebration. Carries
     * the streak tier (1/5/10/25) just crossed when the user finishes a
     * book; [HybridReaderScreen] renders a [LightMotes] burst + an "N
     * books lit" badge for each tier pulled off the channel. Conflated
     * so a missed observer sees the latest signal but never a backlog.
     * The counter increment + tier decision are serialized in the repo
     * seam ([recordBookCompletedAndMilestone]) so this fires at most
     * once per finished book.
     */
    private val _bookStreakTrigger = Channel<Int>(capacity = Channel.CONFLATED)
    val bookStreakTrigger: Flow<Int> = _bookStreakTrigger.receiveAsFlow()

    /** Issue #805 — user-dismissed error message. When the user dismisses
     *  the playback error banner, we record the error message here. The
     *  combine below suppresses the banner when the current error message
     *  matches the dismissed one. A new/different error re-arms the banner
     *  automatically (different message = cleared latch). */
    private val _dismissedErrorMessage = MutableStateFlow<String?>(null)

    /** Issue #677 — end-of-book latch backing the
     *  [ReaderUiState.bookFinished] projection. Engine-driven set in the
     *  [PlaybackUiEvent.BookFinished] arm of the player-events collector;
     *  cleared by [acknowledgeBookFinished] when the user dismisses the
     *  overlay, and by [resume] before starting a fresh listen so a
     *  "Restart from beginning" doesn't immediately re-trip the modal. */
    private val _bookFinished = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            isLoading.collect { loading ->
                loadingTimerJob?.cancel()
                if (!loading) {
                    _loadingPhase.value = LoadingPhase.NotLoading
                    return@collect
                }
                _loadingPhase.value = LoadingPhase.Loading
                loadingTimerJob = launch {
                    delay(LOADING_SLOW_HINT_MS)
                    _loadingPhase.value = LoadingPhase.Slow
                    delay(LOADING_TIMEOUT_MS - LOADING_SLOW_HINT_MS)
                    _loadingPhase.value = LoadingPhase.TimedOut
                }
            }
        }
        // Calliope (v0.5.00) — observe player events for the
        // one-time confetti trigger. We re-check the persisted flag
        // on each ChapterDone (not just at init) so a celebration
        // fired in a different process / install state doesn't
        // double-fire on a quick chapter-complete after reopening.
        viewModelScope.launch {
            playback.events.collect { ev ->
                when (ev) {
                    is PlaybackUiEvent.ChapterDone -> {
                        // Fire the chapter-complete celebration when EITHER
                        // milestone window is live and its one-time flag is
                        // still unshown. v1.1 upgraded the visual from the
                        // old falling confetti to the rising LightMotes; the
                        // same conflated trigger drives both (the host picks
                        // the overlay), so the serialization + one-time gate
                        // are unchanged.
                        val ms = settings.milestoneState.first()
                        val v0500 = ms.qualifies && !ms.confettiShown
                        val v110 = ms.v110Qualifies && !ms.v110ConfettiShown
                        if (v0500 || v110) {
                            _confettiTrigger.trySend(Unit)
                        }
                    }
                    // Issue #677 — engine signaled the last chapter ended
                    // naturally with no successor. Flip the end-of-book latch
                    // so HybridReaderScreen renders the BookFinishedOverlay.
                    is PlaybackUiEvent.BookFinished -> {
                        _bookFinished.value = true
                        // Candela (v1.1.0) — a finished book ticks the
                        // device-local streak counter. The repo seam
                        // serializes increment + tier decision; if this
                        // increment crossed a tier (1/5/10/25) emit it for
                        // the LightMotes + "N books lit" badge.
                        settings.recordBookCompletedAndMilestone()?.let { tier ->
                            _bookStreakTrigger.trySend(tier)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Called by [HybridReaderScreen] when the chapter-complete
     *  celebration overlay fades out, so the one-time flag flips and it
     *  never replays. Flips BOTH milestone flags (Calliope v0.5.00 +
     *  Candela v1.1) — only the live window's flag is read back by the
     *  ChapterDone gate above, and flipping an already-true flag is a
     *  harmless idempotent write, so we don't need to know which
     *  milestone actually fired the burst. */
    fun markConfettiShown() {
        viewModelScope.launch {
            settings.markMilestoneConfettiShown()
            settings.markV110ConfettiShown()
        }
    }

    /** Issue #677 — user dismissed the end-of-book overlay (Back to
     *  Library, Browse more, or backdrop tap). Clear the latch so the
     *  modal doesn't re-trip the next time the screen recomposes. */
    fun acknowledgeBookFinished() {
        _bookFinished.value = false
    }

    /** Issue #805 — user swiped away / tapped X on the playback error
     *  banner. Records the dismissed message so the combine suppresses
     *  the same error. A new/different error re-arms the banner. */
    fun dismissPlaybackError() {
        val current = uiState.value.playbackError
        _dismissedErrorMessage.value = current?.message
    }

    val uiState: StateFlow<ReaderUiState> = combine(
        playback.state,
        playback.chapterText,
        _activePane,
        _loadingPhase,
        // Issue #418 — read the live pause-multiplier + Sonic high-quality
        // flag from SettingsRepositoryUi so the voice quick-sheet renders
        // the same values the Settings screen would, without owning a
        // separate persistence path. Both knobs are global (not per-voice)
        // today, so projecting them onto the per-screen UI state stays
        // sound — when the user flips one in the sheet, the
        // SettingsRepositoryUi flow updates and the next emission flows
        // straight back through this `combine`.
        settings.settings,
        // "Why are we waiting?" — pipe the AudioOutputMonitor flow
        // through ReaderUiState so AudiobookView can render the brass
        // diagnostic panel without taking another flow subscription.
        playback.waitReason,
        // Issue #677 — end-of-book latch; HybridReaderScreen mounts the
        // BookFinishedOverlay when this flips true.
        _bookFinished,
        // Issue #805 — typed engine state for error surfacing. The reader
        // projects EngineState.Error onto ReaderUiState.playbackError so
        // AudiobookView can render a typed error banner with specific
        // messages and recovery actions per error subtype.
        playback.engineState,
        // Issue #805 — dismiss latch for the error banner. When the user
        // swipes away the banner, we record the message; same message =
        // suppressed; new/different message = re-armed.
        _dismissedErrorMessage,
        // Issue #1369 — "Load into Teleprompter" parks a script here; when
        // present, the reader scrolls its body instead of the chapter body.
        teleprompterScriptStore.pending,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val state = values[0] as UiPlaybackState
        @Suppress("UNCHECKED_CAST")
        val text = values[1] as String
        @Suppress("UNCHECKED_CAST")
        val pane = values[2] as ReaderView
        @Suppress("UNCHECKED_CAST")
        val phase = values[3] as LoadingPhase
        val settingsSnapshot = values[4] as `in`.jphe.storyvox.feature.api.UiSettings
        @Suppress("UNCHECKED_CAST")
        val waitReason = values[5] as? `in`.jphe.storyvox.playback.diagnostics.WaitReason
        val bookFinished = values[6] as Boolean
        val engineState = values[7] as `in`.jphe.storyvox.playback.EngineState
        val dismissedMsg = values[8] as? String
        // Issue #1369 — a parked teleprompter script overrides the chapter body
        // as the reader's scroll content (null = normal chapter reading).
        val pendingScript = values[9] as? PendingTeleprompterScript
        val error = engineState as? `in`.jphe.storyvox.playback.EngineState.Error
        ReaderUiState(
            playback = state,
            // Issue #1369 — show the parked script when one is loaded, else the chapter.
            chapterText = pendingScript?.body ?: text,
            activePane = pane,
            loadingPhase = phase,
            punctuationPauseMultiplier = settingsSnapshot.punctuationPauseMultiplier,
            pitchInterpolationHighQuality = settingsSnapshot.pitchInterpolationHighQuality,
            waitReason = waitReason,
            bookFinished = bookFinished,
            // Suppress the banner when the user already dismissed this
            // exact error message; a new/different error re-arms it.
            playbackError = if (error != null && error.message != dismissedMsg) error else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState())

    /**
     * Most-recent continue-listening entry. Drives the magical Resume
     * prompt that [HybridReaderScreen] renders when the player has no
     * chapter loaded yet, or when the loading timer hit [LoadingPhase.TimedOut]
     * with stale ids. Null while the DAO row is unset (first-launch /
     * wiped-data) — in that case the screen renders [ResumeEmptyPrompt]
     * instead. Same flow LibraryViewModel reads for its tile so both
     * surfaces stay in lock-step.
     */
    val resumeEntry: StateFlow<ContinueListeningEntry?> = positionRepo
        .observeMostRecentContinueListening()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val chapters: StateFlow<List<UiChapter>> = playback.state
        .map { it.fictionId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList()) else fictionRepo.chaptersFor(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Issue #1231 — the loaded book's pinned per-fiction speed, or null when
     * it inherits the global default. Observed per-fiction (re-subscribes on a
     * book switch) so the reader's speed-scope toggle reflects the right book.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val perBookSpeed: StateFlow<Float?> = playback.state
        .map { it.fictionId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(null) else fictionRepo.observePlaybackSpeed(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Issue #1231 — whether the speed control's "this book" scope is active
     * (the current book has its own pinned speed). Drives the segmented toggle
     * in the voice quick-sheet and the routing in [persistSpeed].
     */
    val speedIsPerBook: StateFlow<Boolean> = perBookSpeed
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Issue #999 phase 2 — the loaded chapter's saved highlights, observed
     * per-chapter so the reader renders only the current chapter's spans and
     * the observer re-subscribes on a chapter switch (not on every fiction
     * row). Empty while no chapter is loaded. [ReaderTextView] maps each row
     * to a `(charRange, fillColour)` for [SentenceHighlight]'s background span
     * layer, and to the tap-resolution lists for `annotationIdAtOffset`.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val chapterHighlights: StateFlow<List<Annotation>> = playback.state
        .map { it.chapterId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList()) else annotationRepo.observeForChapter(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ─── Issue #1229: whole-book ("find in book") search ──────────────────
    private val _bookSearchOpen = MutableStateFlow(false)
    private val _bookSearchQuery = MutableStateFlow("")
    private val _bookSearchSelected = MutableStateFlow(0)

    /**
     * Debounced query × currently-loaded fiction → refined results, computed
     * off the main thread. `flatMapLatest` cancels a stale search the moment
     * the user types another character or the loaded book changes, so only
     * the newest query's DB scan + [searchBook] pass survives. A query below
     * [MIN_BOOK_SEARCH_QUERY] (or no fiction) collapses to the idle state
     * without touching the DB.
     */
    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val bookSearchResults: StateFlow<BookSearchResultsState> = combine(
        _bookSearchQuery
            .debounce(BOOK_SEARCH_DEBOUNCE_MS)
            .map { it.trim() }
            .distinctUntilChanged(),
        playback.state.map { it.fictionId }.distinctUntilChanged(),
    ) { query, fictionId -> query to fictionId }
        .flatMapLatest { (query, fictionId) ->
            if (query.length < MIN_BOOK_SEARCH_QUERY || fictionId.isNullOrBlank()) {
                flowOf(BookSearchResultsState())
            } else {
                flow {
                    emit(BookSearchResultsState(phase = BookSearchPhase.Searching))
                    val rows = chapterRepo.searchChapterBodies(fictionId, query, DEFAULT_BOOK_SEARCH_LIMIT)
                    val results = searchBook(
                        rows.map { ChapterBody(it.id, it.index, it.title, it.plainBody) },
                        query,
                    )
                    emit(
                        BookSearchResultsState(
                            phase = BookSearchPhase.Done,
                            results = results,
                            // The chapter cap was hit — not every chapter was scanned.
                            truncated = rows.size >= DEFAULT_BOOK_SEARCH_LIMIT,
                        ),
                    )
                }.flowOn(Dispatchers.Default)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookSearchResultsState())

    /**
     * Issue #1229 — merged book-search state for [HybridReaderScreen]'s
     * overlay: open flag, the live query text, the refined results, the
     * stepper selection (clamped into range as the result set changes), and
     * the search phase / truncation flags.
     */
    val bookSearch: StateFlow<BookSearchUiState> = combine(
        _bookSearchOpen,
        _bookSearchQuery,
        bookSearchResults,
        _bookSearchSelected,
    ) { open, query, res, selected ->
        val safeSelected =
            if (res.results.isEmpty()) 0 else selected.coerceIn(0, res.results.lastIndex)
        BookSearchUiState(
            open = open,
            query = query,
            results = res.results,
            selectedIndex = safeSelected,
            phase = res.phase,
            truncated = res.truncated,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookSearchUiState())

    /**
     * Issue #1234 — the current fiction's author, for the share-quote
     * attribution line. The playback state carries the fiction *title* and
     * chapter title but not the author, so we resolve it from the fiction row
     * keyed on the live fictionId (re-subscribing only on a book switch, like
     * [chapters]). Blank while no fiction is loaded or the source has no
     * author; [QuoteShareFormatter] drops a blank author from the attribution.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentAuthor: StateFlow<String> = playback.state
        .map { it.fictionId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf("") else fictionRepo.fictionById(id).map { it?.author.orEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * Issue #946 — magical reader auto-scroll toggle. Default true so
     * existing users keep their read-along behavior; flipping to false
     * frees the chapter body for manual scrolling while audio continues.
     * Backed by [SettingsRepositoryUi.readerAutoScrollEnabled] so the
     * preference rides across sessions and across the InstantDB sync
     * allowlist if/when wired through.
     */
    val autoScrollEnabled: StateFlow<Boolean> = settings.readerAutoScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * Issue #997 — Focused Reading mode toggle. Default false (new
     * opt-in mode, unlike auto-scroll). Backed by
     * [SettingsRepositoryUi.readerFocusModeEnabled]; device-local (not
     * in the sync allowlist). [ReaderTextView] observes this to dim
     * off-focus lines, narrow the column, centre the active line and
     * collapse the bottom chrome.
     */
    val focusModeEnabled: StateFlow<Boolean> = settings.readerFocusModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Issue #993 — resolved reading-theme colours for the reader surface.
     * Default [ReaderColors] is inactive, so the reader renders with the app
     * theme until the user opts into a reading theme. Device-local pref.
     */
    val readerColors: StateFlow<ReaderColors> = settings.settings
        .map { it.readerColors }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderColors())

    /**
     * Issue #992 — reader-surface typography (font family + size + line/letter
     * spacing). Derived from [SettingsRepositoryUi.settings] and fed to the
     * reader via `LocalReaderTypography`. Defaults reproduce the legacy reader
     * style, so the surface is unchanged until the user opts in.
     */
    val readerTypography: StateFlow<ReaderTypography> = settings.settings
        .map { it.readerTypography }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderTypography())

    /**
     * Issue #994 — reading-highlight mode. [ReaderTextView] observes this to
     * drive the per-word karaoke fill (Word / Both) vs the legacy sentence
     * underline (Sentence) or no highlight (Off). Device-local pref; default
     * [HighlightMode.Sentence] keeps today's behaviour.
     */
    val highlightMode: StateFlow<HighlightMode> = settings.settings
        .map { it.highlightMode }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HighlightMode.Sentence)

    /**
     * Issue #994 — custom per-word highlight colour (ARGB int; 0 = unset →
     * derive from the reading-theme accent). Device-local pref.
     */
    val wordHighlightArgb: StateFlow<Int> = settings.settings
        .map { it.wordHighlightArgb }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Issue #1308 — teleprompter control state, now owned by the shared
    // [TeleprompterController] (single source of truth for the phone reader and
    // the Wear remote). The reader collects these and drives them via the
    // setters below; the controller is a @Singleton, so PR2's Wear bridge drives
    // the same state.
    val teleprompterEnabled: StateFlow<Boolean> = teleprompter.enabled
    val teleprompterPlaying: StateFlow<Boolean> = teleprompter.playing

    /** Live rehearsal pace (WPM). Seeded from the persisted pref when the
     *  teleprompter opens and written through on change — see
     *  [setTeleprompterEnabled] / [setTeleprompterWpm] (#1287/#1304). */
    val teleprompterWpm: StateFlow<Int> = teleprompter.wpm

    // Issue #1368 — voice-paced teleprompter. The reader maps
    // [voicePacedPositionChar] (the speaker's live position, from the forced
    // aligner) to a scroll target; [voicePacedModelReady] gates the Voice mode
    // and [voicePacedPreparing] reflects the one-time model download.
    val voicePacedPositionChar: StateFlow<Int> = voicePaced.positionChar

    private val _voicePacedModelReady = MutableStateFlow(asrModel.isReady())
    val voicePacedModelReady: StateFlow<Boolean> = _voicePacedModelReady.asStateFlow()

    private val _voicePacedPreparing = MutableStateFlow(false)
    val voicePacedPreparing: StateFlow<Boolean> = _voicePacedPreparing.asStateFlow()

    /** Issue #278 — user-initiated retry from the timed-out error block.
     *  Re-invokes the playback `play()` path; the underlying controller
     *  will re-fetch the chapter / re-prime the voice. We also reset the
     *  loading phase so the UI immediately shows the regular spinner
     *  instead of staying on the error block.
     *
     *  No fictionId/chapterId check — the playback controller is the
     *  source of truth for what's "currently loaded"; if neither is set,
     *  play() is a documented no-op. */
    fun retryLoading() {
        _loadingPhase.value = LoadingPhase.Loading
        playback.play()
    }

    /**
     * Playing-tab Resume CTA. Loads the chapter referenced by
     * [resumeEntry], seeks to [ContinueListeningEntry.charOffset] (or to
     * the chapter head when [fromStart] is true), and — per the smart-
     * resume policy (#90) — auto-plays iff the user's last play/pause
     * intent was play. App-killed-mid-playback leaves the flag at true,
     * so the dominant "phone died, keep going" case still auto-plays.
     *
     * No screen transition: the playback state flow drives the
     * Playing-tab content swap from [ResumePrompt] to the normal
     * AudiobookView the moment `playback.state.fictionId` flips
     * non-null. The user feels the prompt dissolve into the player
     * rather than a discrete navigation.
     *
     * Also resets [_loadingPhase] back to `Loading` so the timed-out
     * error path's escape hatch (Lyra: ResumePrompt as the recovery UI
     * for TimedOut) restarts the 30 s timer cleanly — if the new load
     * also stalls, the user gets the same friendly error block on the
     * next round, not an immediate re-fire of the stale `TimedOut` from
     * the previous attempt.
     */
    fun resume(fromStart: Boolean = false) {
        val entry = resumeEntry.value ?: return
        _loadingPhase.value = LoadingPhase.Loading
        // Issue #677 — clear the end-of-book latch on a fresh listen so
        // the overlay doesn't re-trip when the user taps "Restart from
        // beginning" after finishing the book.
        _bookFinished.value = false
        viewModelScope.launch {
            val autoPlay = resumePolicy.currentLastWasPlaying()
            playback.startListening(
                fictionId = entry.fiction.id,
                chapterId = entry.chapter.id,
                charOffset = if (fromStart) 0 else entry.charOffset,
                autoPlay = autoPlay,
            )
        }
    }

    fun setActivePane(pane: ReaderView) { _activePane.value = pane }

    fun playPause() {
        val state = uiState.value.playback ?: return
        if (state.isPlaying) playback.pause() else playback.play()
    }

    fun seekTo(ms: Long) = playback.seekTo(ms)
    fun seekToChar(charOffset: Int) = playback.seekToChar(charOffset)

    /**
     * Issue #999 phase 2 — create a highlight over the user's text selection.
     * The reader hands us the normalised `[startOffset, endOffset)` range (via
     * `normalizeSelection`), the [quotedText] snapshot, the chosen palette
     * [colorName], and an optional [note]. We mint a client-generated UUID
     * (the stable cross-device sync key — see [Annotation]'s identity note) and
     * upsert. fictionId / chapterId come from the live playback state; a
     * blank-id window (cold-load) drops the write rather than persisting an
     * orphan FK. Fire-and-forget on `viewModelScope`; the [chapterHighlights]
     * observer re-emits so the new span renders without an explicit refresh.
     */
    fun createHighlight(
        startOffset: Int,
        endOffset: Int,
        quotedText: String,
        colorName: String,
        note: String? = null,
    ) {
        val state = uiState.value.playback ?: return
        val fictionId = state.fictionId ?: return
        val chapterId = state.chapterId ?: return
        if (endOffset <= startOffset) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            annotationRepo.upsert(
                Annotation(
                    id = java.util.UUID.randomUUID().toString(),
                    fictionId = fictionId,
                    chapterId = chapterId,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    color = colorName,
                    note = note?.takeIf { it.isNotBlank() },
                    quotedText = quotedText,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    /**
     * Issue #999 phase 2 — edit an existing highlight's colour and/or note.
     * REPLACE-on-id keeps it one row (no duplicate); [createdAt] and the
     * offsets/quote are preserved from [existing] so an edit never moves or
     * re-quotes the highlight — only [updatedAt] bumps, which orders the LWW
     * sync and the per-fiction list. The reader passes the current [Annotation]
     * it resolved from the tap, so we don't re-read the DAO on the edit path.
     */
    fun updateHighlight(existing: Annotation, colorName: String, note: String?) {
        viewModelScope.launch {
            annotationRepo.upsert(
                existing.copy(
                    color = colorName,
                    note = note?.takeIf { it.isNotBlank() },
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Issue #999 phase 2 — delete a highlight by id (the edit sheet's Delete). */
    fun deleteHighlight(id: String) {
        viewModelScope.launch { annotationRepo.delete(id) }
    }

    // ── Issue #1230 — tap-to-define dictionary ────────────────────────────
    private val _dictionary = MutableStateFlow<DictionaryUiState>(DictionaryUiState.Hidden)

    /** Tap-to-define popup state. [HybridReaderScreen] collects this and hands
     *  it to [ReaderTextView], which renders the bottom sheet. */
    val dictionary: StateFlow<DictionaryUiState> = _dictionary.asStateFlow()

    /** The in-flight lookup, cancelled when a new word is requested or the
     *  sheet is dismissed so a slow response can't clobber a fresh one. */
    private var dictionaryJob: Job? = null

    /**
     * Issue #1230 — look up [rawWord] (the token under a reader long-press) and
     * drive [dictionary] through Loading → Loaded / Empty / Error.
     *
     * Normalisation (strip punctuation / possessives, reject non-words) is the
     * pure [normalizeLookupWord]; a token with no letters short-circuits to
     * [DictionaryUiState.Empty] without a network call, so the sheet still opens
     * with the fallbacks rather than spinning on a hopeless query. The previous
     * lookup is cancelled so rapid long-presses don't race.
     */
    fun defineWord(rawWord: String) {
        val normalized = normalizeLookupWord(rawWord)
        if (normalized == null) {
            dictionaryJob?.cancel()
            _dictionary.value = DictionaryUiState.Empty(rawWord.trim())
            return
        }
        dictionaryJob?.cancel()
        _dictionary.value = DictionaryUiState.Loading(normalized)
        dictionaryJob = viewModelScope.launch {
            _dictionary.value = when (val result = dictionaryRepo.define(normalized)) {
                is DictionaryResult.Success -> DictionaryUiState.Loaded(result.definition)
                is DictionaryResult.NotFound -> DictionaryUiState.Empty(result.word)
                is DictionaryResult.Error -> DictionaryUiState.Error(result.word, result.message)
            }
        }
    }

    /** Re-run the lookup for [word] (the Error row's Retry). */
    fun retryDefine(word: String) = defineWord(word)

    /** Close the tap-to-define sheet and drop any in-flight lookup. */
    fun dismissDefinition() {
        dictionaryJob?.cancel()
        dictionaryJob = null
        _dictionary.value = DictionaryUiState.Hidden
    }

    fun skipForward() = playback.skipForward()
    fun skipBack() = playback.skipBack()
    fun nextChapter() = playback.nextChapter()
    fun previousChapter() = playback.previousChapter()

    fun playChapter(chapterId: String) {
        val fictionId = uiState.value.playback?.fictionId ?: return
        _loadingPhase.value = LoadingPhase.Loading
        _bookFinished.value = false
        playback.startListening(fictionId = fictionId, chapterId = chapterId)
    }

    // ─── Issue #1229: book-search actions ─────────────────────────────────
    fun openBookSearch() { _bookSearchOpen.value = true }

    fun closeBookSearch() {
        _bookSearchOpen.value = false
        _bookSearchQuery.value = ""
        _bookSearchSelected.value = 0
    }

    fun setBookSearchQuery(query: String) {
        _bookSearchQuery.value = query
        // A fresh query re-bases the stepper at the first result.
        _bookSearchSelected.value = 0
    }

    /** Step the result selection forward / back with wraparound, reusing the
     *  #998 cycling math so an empty list is a safe no-op. */
    fun selectNextResult() {
        val count = bookSearch.value.results.size
        if (count > 0) {
            _bookSearchSelected.value = nextMatchIndex(bookSearch.value.selectedIndex, count)
        }
    }

    fun selectPreviousResult() {
        val count = bookSearch.value.results.size
        if (count > 0) {
            _bookSearchSelected.value = prevMatchIndex(bookSearch.value.selectedIndex, count)
        }
    }

    /**
     * Navigate to a search result: load its chapter at the first match's char
     * offset and land on the reader (text) pane so the hit is visible — the
     * existing auto-scroll (#946/#919) settles the seeked sentence into view.
     * We deliberately don't auto-play: the user asked to *find* a passage, not
     * to start narration there; they can press play. Closes the overlay; a
     * blank-id window (cold-load) drops the tap rather than firing a malformed
     * load.
     */
    fun openBookSearchResult(result: BookSearchResult) {
        val fictionId = uiState.value.playback?.fictionId ?: return
        closeBookSearch()
        _loadingPhase.value = LoadingPhase.Loading
        _bookFinished.value = false
        _activePane.value = ReaderView.Reader
        playback.startListening(
            fictionId = fictionId,
            chapterId = result.chapterId,
            charOffset = result.matchOffset,
            autoPlay = false,
        )
    }
    fun nextSentence() = playback.nextSentence()
    fun previousSentence() = playback.previousSentence()
    /** #1001 — paragraph-level transport, delegated to playback. */
    fun nextParagraph() = playback.nextParagraph()
    fun previousParagraph() = playback.previousParagraph()

    fun setSpeed(speed: Float) {
        playback.setSpeed(speed)
    }

    /**
     * Issue #1231 — commit a speed change to the active scope. When the
     * current book is pinned ("this book"), the new speed updates that book's
     * override; otherwise it updates the global default. The reader's speed
     * chips + slider both route through here on commit; the auto-restore flow
     * in RealPlaybackControllerUi reacts to whichever store changed.
     */
    fun persistSpeed(speed: Float) {
        val fictionId = uiState.value.playback?.fictionId
        viewModelScope.launch {
            if (fictionId != null && speedIsPerBook.value) {
                fictionRepo.setPlaybackSpeed(fictionId, speed)
            } else {
                settings.setDefaultSpeed(speed)
            }
        }
    }

    /**
     * Issue #1231 — flip the speed-scope for the current book. `perBook = true`
     * pins the book to the speed it's playing at right now; `false` clears the
     * pin so the book reverts to the global/effective default. A pin/clear is
     * all this does — the live engine speed is re-resolved by the auto-restore
     * flow reacting to the persisted change. No-op when no book is loaded.
     */
    fun toggleSpeedScope(perBook: Boolean) {
        val fictionId = uiState.value.playback?.fictionId ?: return
        val currentSpeed = uiState.value.playback?.speed ?: 1.0f
        viewModelScope.launch {
            fictionRepo.setPlaybackSpeed(fictionId, if (perBook) currentSpeed else null)
        }
    }

    fun setPitch(pitch: Float) {
        playback.setPitch(pitch)
    }

    fun persistPitch(pitch: Float) {
        viewModelScope.launch { settings.setDefaultPitch(pitch) }
    }

    fun startSleepTimer(mode: UiSleepTimerMode) = playback.startSleepTimer(mode)
    fun cancelSleepTimer() = playback.cancelSleepTimer()

    /**
     * Issue #418 — set the inter-sentence pause multiplier from the
     * magical-voice-icon quick sheet. Mirrors the Settings → Voice &
     * Playback "Punctuation pause" slider (#109): the engine clamps to
     * [0..4×], the change takes effect on the next sentence boundary.
     * We hit both seams — [PlaybackControllerUi.setPunctuationPauseMultiplier]
     * for the immediate live-apply (engine re-reads on next sentence),
     * and [SettingsRepositoryUi.setPunctuationPauseMultiplier] for
     * persistence so the value survives app restart. The Settings screen
     * uses the same dual-write pattern for speed/pitch via
     * `persistSpeed`/`persistPitch`.
     */
    fun setPunctuationPauseMultiplier(multiplier: Float) {
        playback.setPunctuationPauseMultiplier(multiplier)
        viewModelScope.launch { settings.setPunctuationPauseMultiplier(multiplier) }
    }

    /**
     * Issue #418 — toggle the Sonic high-quality pitch-interpolation
     * flag from the voice quick sheet. Mirrors the Settings switch
     * (#193). Persisted-only — the engine reads the flag at the
     * start of each chapter render, so a mid-chapter flip applies to
     * the next chapter rather than immediately. The quick-sheet UI
     * surface still calls out "applies on next chapter" via subtitle
     * copy so the listener isn't surprised when the current chapter
     * doesn't change tone.
     */
    fun setPitchInterpolationHighQuality(enabled: Boolean) {
        viewModelScope.launch { settings.setPitchInterpolationHighQuality(enabled) }
    }

    /**
     * Issue #946 — persist the reader auto-scroll toggle. Mirrored
     * shape to the other setting setters: fire-and-forget into the
     * viewModelScope, the StateFlow above re-emits, the
     * [ReaderTextView] observes and re-gates its scroll
     * LaunchedEffect.
     */
    fun setAutoScrollEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setReaderAutoScrollEnabled(enabled) }
    }

    /** Issue #997 — persist the Focused Reading toggle. Same
     *  fire-and-forget shape as [setAutoScrollEnabled]; the StateFlow
     *  re-emits and [ReaderTextView] re-renders in focus mode. */
    fun setFocusModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setReaderFocusModeEnabled(enabled) }
    }

    /** Issue #1308 — drive the shared teleprompter controls. The reader calls
     *  these (and, in PR2, the Wear bridge); the controller's StateFlows re-emit
     *  so every observer stays in sync. */
    fun setTeleprompterEnabled(enabled: Boolean) {
        teleprompter.setEnabled(enabled)
        // Issue #1287/#1304 — seed the live pace from the persisted pref each
        // time the teleprompter opens, so a restart restores the last pace. Read
        // the current pref on demand (the settings flow is cached) rather than a
        // hot StateFlow, so the seed is the real saved value, not a placeholder.
        if (enabled) {
            viewModelScope.launch {
                teleprompter.setWpm(settings.settings.first().teleprompterWpm)
            }
        } else {
            // Issue #1369 — turning the teleprompter off ends the rehearsal
            // session, so drop any parked script and revert to the chapter body.
            teleprompterScriptStore.clear()
        }
    }

    fun setTeleprompterPlaying(playing: Boolean) {
        teleprompter.setPlaying(playing)
    }

    /** Issue #1287/#1304 — update the live pace AND persist it (the impl clamps
     *  to the supported band; the next open re-seeds from the saved value). */
    fun setTeleprompterWpm(wpm: Int) {
        teleprompter.setWpm(wpm)
        viewModelScope.launch { settings.setTeleprompterWpm(wpm) }
    }

    /** Issue #1308 — reset the transient controls when the reader leaves
     *  composition. The [TeleprompterController] is a @Singleton, so without
     *  this it would retain `enabled` across navigation — a behavior change from
     *  #1239's per-visit-transient local state. */
    fun resetTeleprompter() {
        teleprompter.setEnabled(false)
        teleprompter.setPlaying(false)
        voicePaced.stop() // #1368 — never leave the mic capturing after the reader closes
        teleprompterScriptStore.clear() // #1369 — drop any parked script when the reader closes
    }

    // ── Voice-paced teleprompter (issue #1368) ─────────────────────────

    /** Begin a voice-paced session over [chapterText]: the controller starts
     *  mic capture + STT and streams the speaker's position to
     *  [voicePacedPositionChar]. The reader brackets this with the active
     *  window (model + permission satisfied). */
    fun startVoicePaced(chapterText: String) = voicePaced.start(chapterText)

    /** End the voice-paced session and release the recognizer. Idempotent. */
    fun stopVoicePaced() = voicePaced.stop()

    /** Kick the one-time on-device STT model download, flipping
     *  [voicePacedModelReady] when it lands. Idempotent — ignored while a
     *  download is in flight or the model is already present. A failed
     *  download just clears the spinner; the user can retry. */
    fun prepareVoicePaced() {
        if (_voicePacedPreparing.value || _voicePacedModelReady.value) return
        _voicePacedPreparing.value = true
        viewModelScope.launch {
            asrModel.download().collect { progress ->
                when (progress) {
                    is AsrModelProvider.DownloadProgress.Done -> {
                        _voicePacedModelReady.value = asrModel.isReady()
                        _voicePacedPreparing.value = false
                    }
                    is AsrModelProvider.DownloadProgress.Failed -> {
                        _voicePacedPreparing.value = false
                    }
                    else -> Unit // Resolving / Downloading — keep the spinner up
                }
            }
        }
    }

    // Issue #121 — in-chapter bookmark fan-out. ReaderViewModel stays
    // the single seam ChapterView talks to; controller delegate handles
    // the suspend-vs-fire-and-forget seam internally.
    fun bookmarkHere() = playback.bookmarkHere()
    fun clearBookmark() = playback.clearBookmark()
    fun jumpToBookmark() = playback.jumpToBookmark()

    // ── Chapter Recap (issue #81) ──────────────────────────────────

    /** Open the modal and stream a recap for the current chapter.
     *  No-op if the playback state isn't ready (no fictionId/chapterId
     *  to recap). */
    fun requestRecap() {
        // Cancel any in-flight recap so we don't double-stream into
        // the buffer.
        recapJob?.cancel()
        val state = uiState.value.playback ?: return
        val fictionId = state.fictionId ?: return
        val chapterId = state.chapterId ?: return

        _recap.value = RecapUiState.Loading
        val buf = StringBuilder()
        recapJob = viewModelScope.launch {
            chapterRecap.recap(fictionId, chapterId)
                .catch { e ->
                    _recap.value = mapErrorToUi(e)
                }
                .onCompletion { cause ->
                    if (cause == null && _recap.value is RecapUiState.Streaming) {
                        _recap.value = RecapUiState.Done(buf.toString())
                    } else if (cause == null && _recap.value === RecapUiState.Loading) {
                        // Stream completed without emitting anything
                        // (e.g. no chapters in window).
                        _recap.value = RecapUiState.Done(buf.toString())
                    }
                    // Cancelled (cause == CancellationException) →
                    // leave whatever state we were in; the caller
                    // (cancelRecap) flips us to Hidden directly.
                }
                .collect { delta ->
                    buf.append(delta)
                    _recap.value = RecapUiState.Streaming(buf.toString())
                }
        }
    }

    /** Hide the modal. Cancels the in-flight stream — partial recap
     *  is discarded. Also stops any in-flight recap-aloud utterance
     *  (#189) so closing the modal silences the audio. */
    fun cancelRecap() {
        recapJob?.cancel()
        recapJob = null
        playback.stopSpeaking()
        _recap.value = RecapUiState.Hidden
    }

    /**
     * Issue #189 — toggle the recap-aloud TTS. Tapped from the Read-aloud
     * button in [RecapModal] when the recap is in [RecapUiState.Done].
     *
     * Behaviour:
     *  - If a recap utterance is already speaking, stop it. (Button
     *    rendered as a Pause icon — second tap silences.)
     *  - Otherwise, pause the active fiction (so the recap and the
     *    chapter audio don't overlap), then synthesize the recap text
     *    via the active voice. Per the spec we leave fiction paused
     *    when the recap finishes — auto-resume would feel aggressive.
     */
    fun toggleRecapAloud() {
        if (recapPlayback.value == UiRecapPlaybackState.Speaking) {
            playback.stopSpeaking()
            return
        }
        val text = (recap.value as? RecapUiState.Done)?.text ?: return
        if (text.isBlank()) return
        // Pause active fiction first — engine is shared, overlapping audio
        // would be muddy.
        if (uiState.value.playback?.isPlaying == true) playback.pause()
        viewModelScope.launch {
            playback.speakText(text)
        }
    }

    companion object {
        /** Issue #278 — soft hint threshold. After 10s of being stuck in
         *  the loading state, the AudiobookView adds a "Still working…
         *  (slow voice / network)" secondary line under the conjuring
         *  copy. The threshold is conservative — real warmups on Flip3
         *  routinely take 5-8s, so 10s is the cutoff between "expected"
         *  and "starting to feel off." */
        const val LOADING_SLOW_HINT_MS = 10_000L

        /** Issue #278 — hard timeout threshold. At 30s we flip to a
         *  user-actionable error: Retry / Pick voice / Cancel. The
         *  underlying load isn't cancelled — if it eventually completes
         *  the regular state flow takes over again — but the user has
         *  a way out of the conjuring screen instead of staring forever. */
        const val LOADING_TIMEOUT_MS = 30_000L
    }

    private fun mapErrorToUi(e: Throwable): RecapUiState.Error = when (e) {
        is LlmError.NotConfigured -> RecapUiState.Error(
            message = "Set up AI in Settings to use Recap.",
            kind = RecapUiState.ErrorKind.NotConfigured,
        )
        is LlmError.AuthFailed -> RecapUiState.Error(
            message = "${e.provider} key is invalid — check Settings.",
            kind = RecapUiState.ErrorKind.AuthFailed,
        )
        is LlmError.Transport -> RecapUiState.Error(
            message = "Couldn't reach the AI — check your connection and try again.",
            kind = RecapUiState.ErrorKind.Transport,
        )
        is LlmError.ProviderError -> RecapUiState.Error(
            message = "AI service error (${e.status}). Try again in a moment.",
            kind = RecapUiState.ErrorKind.ProviderError,
        )
        else -> RecapUiState.Error(
            message = e.message ?: "Recap failed.",
            kind = RecapUiState.ErrorKind.Transport,
        )
    }
}
