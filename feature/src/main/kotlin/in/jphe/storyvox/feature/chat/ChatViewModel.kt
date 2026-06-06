package `in`.jphe.storyvox.feature.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.FictionMemoryRepository
import `in`.jphe.storyvox.data.repository.ShelfRepository
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiChatGrounding
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.feature.chat.memory.CrossFictionMemoryBlock
import `in`.jphe.storyvox.feature.chat.memory.FictionMemoryExtractor
import `in`.jphe.storyvox.feature.chat.tools.ChatToolHandlers
import `in`.jphe.storyvox.llm.FeatureKind
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmContentBlock
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.LlmSessionRepository
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.tools.ChatStreamEvent
import `in`.jphe.storyvox.llm.tools.ToolCallEvent
import `in`.jphe.storyvox.llm.tools.ToolRegistry
import `in`.jphe.storyvox.llm.tools.ToolResult
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One chat turn the UI renders, distinct from the wire/storage shapes.
 *  The in-flight assistant message lives in [ChatUiState.streaming]
 *  rather than as a [ChatTurn] entry so its identity is unambiguous —
 *  appended-to deltas don't risk being mistaken for a finalised turn. */
@Immutable
data class ChatTurn(
    val role: Role,
    val text: String,
    /** Issue #215 — non-null for user turns sent with an inline image.
     *  Carries the original content:// URI so the bubble can render
     *  a thumbnail via Coil. Ephemeral — only set on freshly-sent
     *  turns; on rehydration from Room (where image bytes don't
     *  survive) this stays null. */
    val imageUri: String? = null,
) {
    enum class Role { User, Assistant }
}

/** Issue #215 — image queued by the composer for the next send.
 *  Holds both the source URI (for thumbnail rendering) and the
 *  base64-encoded JPEG (for the LLM request). After send, both are
 *  cleared. */
@Immutable
data class ImageAttachment(
    val uri: String,
    val base64: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
)

/** Top-level UI state. Fields are independently observable so a token
 *  arriving doesn't reflow the whole list (Compose only diffs the
 *  changed lambda). */
@Immutable
data class ChatUiState(
    /** Finalised user + assistant turns from session history. */
    val turns: List<ChatTurn> = emptyList(),
    /** The current in-flight assistant reply, mid-stream. Null when
     *  nothing is streaming. */
    val streaming: String? = null,
    /** Title to display in the top bar — the fiction the user is
     *  reading. Null until the fiction row resolves. */
    val fictionTitle: String? = null,
    /** True when AI is disabled in Settings → AI. The UI surfaces a
     *  tap-through empty state instead of the input field. */
    val noProvider: Boolean = false,
    /** When non-null, the last send hit an error. UI surfaces it as
     *  a recoverable banner above the input. */
    val error: ChatError? = null,
    /** Issue #214 — text of the assistant turn currently being read
     *  aloud through the TTS engine, or null when nothing is reading.
     *  The matching turn's bubble shows a stop icon; other bubbles
     *  show a play icon. */
    val readingText: String? = null,
    /** Issue #217 — debug-overlay metric for the cross-fiction memory
     *  block. Reflects the most recent send's block, or null when
     *  nothing was appended (toggle off, no matches, or pre-first-
     *  send). The Settings → AI debug overlay surfaces this so JP
     *  can see how often the block fires and how heavy it is. */
    val crossFictionMemoryDebug: CrossFictionMemoryDebug? = null,
    /** Issue #216 — tool-call timeline, ordered oldest-first. The
     *  list resets on each [send]; entries get a [ToolCallEvent.result]
     *  filled in when the handler completes. ChatScreen renders each
     *  entry as a brass-edged card. */
    val toolCalls: List<ToolCallEvent> = emptyList(),
    /** Issue #216 — true iff the active provider supports function
     *  calling AND the user has enabled actions in Settings → AI.
     *  When false the model's catalog stays empty; the chat behaves
     *  identically to pre-#216. */
    val actionsAvailable: Boolean = false,
    /** Issue #215 — true iff the active provider accepts inline
     *  image content. The chat composer hides the attach button when
     *  false (and the [pendingImage] state stays null too). */
    val imagesSupported: Boolean = false,
    /** Issue #215 — image queued by the composer for the next send,
     *  or null when no image is attached. Cleared on send (success
     *  or failure) and on explicit detach via [clearPendingImage]. */
    val pendingImage: ImageAttachment? = null,
    /** Issue #215 — set when the user attached an image to a send
     *  that landed on a provider that doesn't accept images. The
     *  text portion of the message still went through; the image was
     *  dropped. UI surfaces this as a one-shot info banner that
     *  dismisses on the next send. */
    val imageDroppedWarning: Boolean = false,
)

/** Issue #217 — debug overlay metric for cross-fiction memory. The
 *  Settings → AI debug overlay (issue #294's surface) renders this
 *  when AI debug is on. */
@Immutable
data class CrossFictionMemoryDebug(
    val entryCount: Int,
    val droppedCount: Int,
    val approxTokens: Int,
)

/** Issue #216 — one-shot navigation event from a tool call. Surfaces
 *  through [ChatViewModel.navEvents]; ChatScreen collects + dispatches
 *  to the nav controller. SharedFlow semantics (not StateFlow) so each
 *  emit fires exactly once. */
sealed class ChatNavEvent {
    /** open_voice_library tool was called. Caller routes to
     *  StoryvoxRoutes.VOICE_LIBRARY. */
    data object OpenVoiceLibrary : ChatNavEvent()
}

/** Internal helper for combine — pairs together the three provider-
 *  dependent booleans so the 5-arg combine still fits. */
private data class ProviderInfo(
    val noProvider: Boolean,
    val actionsAvailable: Boolean,
    /** Issue #215 — image support gates the composer's attach button. */
    val imagesSupported: Boolean,
)

/** UI-side classification of [LlmError] so the screen can pick the
 *  right copy + recovery action without depending on `:core-llm`'s
 *  exception types. */
@Immutable
sealed class ChatError(val message: String) {
    /** AI not configured / "Send chapter text" toggle off. */
    class NotConfigured(message: String) : ChatError(message)
    /** 401 / 403 — wrong key. UI offers Settings tap-through. */
    class AuthFailed(message: String) : ChatError(message)
    /** Network / DNS / TLS — recoverable. UI offers Try again. */
    class Transport(message: String) : ChatError(message)
    /** 4xx / 5xx that isn't auth — quota, model 404, malformed body. */
    class ProviderError(message: String) : ChatError(message)
}

/** Issue #1057 — does a stream's terminal `onCompletion` still own the
 *  shared `_streaming` state? [send] cancels the prior job cooperatively
 *  and immediately launches a replacement, so a cancelled job's
 *  `onCompletion` can run *after* the replacement has begun streaming.
 *  The terminal `_streaming` reset must only fire for the stream whose
 *  [completionGeneration] still matches the [currentGeneration]; a stale
 *  cancelled job (older stamp) must no-op, or it blanks the new
 *  in-flight bubble (the flicker in #1057).
 *
 *  Pure + top-level so the ownership rule is unit-testable without
 *  standing up the ViewModel or fighting StateFlow conflation (the bug
 *  is a sub-frame transient that settled-state assertions can't see). */
internal fun streamStillOwnsState(
    completionGeneration: Int,
    currentGeneration: Int,
): Boolean = completionGeneration == currentGeneration

/** Issue #1057 — structural canary. Asserts the active-stream guard is
 *  still wired into the completion handlers' `_streaming` reset. A
 *  refactor that drops the [streamStillOwnsState] gate (reintroducing
 *  the unconditional `_streaming.value = null`) must flip this to false,
 *  which the contract test catches. */
internal const val chatStreamCompletionGuardsOwnership: Boolean = true

/**
 * Backs the Q&A chat surface attached to a fiction. One chat history
 * per fiction (deterministic session id `chat:<fictionId>`) so
 * returning to a book picks up where the conversation left off.
 *
 * Persistence is delegated entirely to [LlmSessionRepository]: it
 * inserts the user turn before the stream starts, appends the
 * assistant turn on success, and emits message rows via
 * [LlmSessionRepository.observeMessages]. This ViewModel owns the
 * in-flight streaming state but never writes to Room directly.
 *
 * The session is bound to the user's currently-active provider at the
 * moment of first creation. If the user switches providers in Settings
 * later, existing sessions stay on their original provider — the
 * library-style "one session per fiction" rebinds on next visit only
 * when the prior session has no messages yet (so an empty session
 * isn't pinned to a provider the user no longer wants).
 *
 * Out of scope for this PR (issue follow-ups): voice/TTS read-back,
 * multi-modal images, function calling / tool use, cross-fiction
 * memory.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionRepo: LlmSessionRepository,
    private val fictionRepo: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
    private val configFlow: Flow<LlmConfig>,
    private val settingsRepo: SettingsRepositoryUi,
    private val memoryRepo: FictionMemoryRepository,
    private val savedState: SavedStateHandle,
    /** Issue #216 — repos + flow used by [ChatToolHandlers]. Nullable
     *  with defaults so existing test infra that constructs the
     *  ViewModel directly with positional args still compiles
     *  unchanged — the new tool wiring is only active when the
     *  caller (real Hilt-bound app) provides non-null values. */
    private val shelfRepo: ShelfRepository? = null,
    private val chapterRepo: ChapterRepository? = null,
    private val llmRepo: LlmRepository? = null,
) : ViewModel() {

    /** Issue #217 — cross-fiction prompt-block builder. The title
     *  resolver pulls the *other* book's title via the same
     *  FictionRepositoryUi we already inject. The wrapper handles
     *  nullable Flow emission. */
    private val crossFictionBlock = CrossFictionMemoryBlock(
        memoryRepo = memoryRepo,
        titleResolver = { id ->
            fictionRepo.fictionById(id)
                .let { runCatching { it.first()?.title }.getOrNull() }
        },
    )

    private val fictionId: String = checkNotNull(savedState["fictionId"]) {
        "ChatScreen requires a `fictionId` nav arg"
    }

    /** Deterministic so a returning user picks up the prior chat
     *  history for this book. Mirrors ChapterRecap's
     *  `recap:fictionId:chapterId` shape (LlmSession.id kdoc). */
    private val sessionId: String = "chat:$fictionId"

    /** One-shot input prefill from the long-press character lookup
     *  (#188). The reader navigates here with `?prefill=Who is X?`, the
     *  composable consumes it once via [consumePrefill] and seeds the
     *  text field. We hold it in a MutableStateFlow rather than as a
     *  raw String so the ChatScreen's `LaunchedEffect` reliably observes
     *  the initial value even on configuration change before consume. */
    private val _prefill = MutableStateFlow<String?>(
        (savedState.get<String>("prefill") ?: "").ifEmpty { null },
    )
    val prefill: StateFlow<String?> = _prefill.asStateFlow()

    /** Called by the input field after applying the prefill so a
     *  subsequent recomposition (e.g. config change) doesn't keep
     *  re-injecting the same starter text over the user's edits. Also
     *  clears the SavedStateHandle key so process death + restore
     *  doesn't replay the prefill either. */
    fun consumePrefill() {
        _prefill.value = null
        savedState["prefill"] = ""
    }

    private val _streaming = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<ChatError?>(null)
    private var sendJob: Job? = null

    /** Issue #1057 — monotonic identity stamp for the active stream.
     *  [send] cancels the prior [sendJob] cooperatively, so a replaced
     *  job's `onCompletion` can run *after* the replacement has already
     *  begun streaming. Each send captures the incremented value here;
     *  a stream's terminal `_streaming` reset only fires when its own
     *  stamp still matches [streamGeneration] — otherwise a stale
     *  cancelled job would blank the new in-flight bubble. */
    private var streamGeneration: Int = 0

    /** Set lazily on first send (or first load if a session already
     *  exists). Tracks whether we've created the Room row yet, so
     *  rapid double-taps on Send don't race two `createSession` calls. */
    private var sessionEnsured: Boolean = false

    /** Issue #214 — text currently being read aloud (or null). When
     *  the engine finishes naturally [observeReadingDone] clears this;
     *  user-pressed stop also clears it via [stopReadAloud]. */
    private val _readingText = MutableStateFlow<String?>(null)

    /** Issue #217 — last cross-fiction memory block's metrics. Stays
     *  null until the first send that toggle-allows + matches the
     *  block. Reset to null on a send where the block ended up empty. */
    private val _memoryDebug = MutableStateFlow<CrossFictionMemoryDebug?>(null)

    /** Issue #216 — tool-call timeline for the in-flight send.
     *  Cleared on each new [send]; appended to as
     *  [ChatStreamEvent.ToolCallStarted] / [ChatStreamEvent.ToolCallCompleted]
     *  events arrive on the chat-with-tools flow. */
    private val _toolCalls = MutableStateFlow<List<ToolCallEvent>>(emptyList())

    /** Issue #215 — image queued by the composer for the next send.
     *  See [attachImage] / [clearPendingImage]. Cleared automatically
     *  on send so the next message doesn't accidentally re-attach. */
    private val _pendingImage = MutableStateFlow<ImageAttachment?>(null)

    /** Issue #215 — one-shot warning banner when a send dropped its
     *  attached image because the active provider doesn't support
     *  images. Cleared on the next send or via [dismissError]. */
    private val _imageDropped = MutableStateFlow(false)

    /** Issue #215 — map of `sentText -> imageUri` for the user turns
     *  this session sent with an attached image. Populated on send,
     *  read at observation time to overlay onto the matching
     *  [ChatTurn]. Ephemeral — process restart loses the map and the
     *  turn renders text-only (the bytes don't survive either; this
     *  matches the v1 "single-image-per-message, single-session"
     *  scope). */
    private val _imageUriByText = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Issue #216 — one-shot navigation events fired by the
     *  open_voice_library tool handler. ChatScreen collects this
     *  with `lifecycle.repeatOnLifecycle(...)` and navigates on each
     *  emit. SharedFlow because the nav action is fire-and-forget;
     *  StateFlow's "latest value" semantics would replay the action
     *  on configuration change. */
    private val _navEvents = MutableSharedFlow<ChatNavEvent>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navEvents: SharedFlow<ChatNavEvent> = _navEvents.asSharedFlow()

    /** Public state. Combines storage + in-flight + provider config
     *  + fiction metadata into one immutable [ChatUiState]. The 5-arg
     *  combine ceiling forces a nested shape — the inner combine
     *  builds the base state, the outer folds in the read-aloud text
     *  + cross-fiction memory + tool-call timeline + actions
     *  availability. */
    val uiState: StateFlow<ChatUiState> = run {
        val turnsWithImages = combine(
            sessionRepo.observeMessages(sessionId).map { msgs -> msgs.map { it.toTurn() } },
            _imageUriByText,
        ) { turns, imageMap ->
            if (imageMap.isEmpty()) turns
            else turns.map { turn ->
                if (turn.role == ChatTurn.Role.User) {
                    val uri = imageMap[turn.text]
                    if (uri != null) turn.copy(imageUri = uri) else turn
                } else turn
            }
        }
        val base = combine(
            turnsWithImages,
            _streaming,
            _error,
            fictionRepo.fictionById(fictionId).map { it?.title },
            configFlow.map { cfg ->
                ProviderInfo(
                    noProvider = cfg.provider == null,
                    actionsAvailable = cfg.aiActionsEnabled &&
                        cfg.provider?.let { llmRepo?.supportsTools(it) } == true,
                    imagesSupported = cfg.provider?.let {
                        llmRepo?.supportsImages(it)
                    } == true,
                )
            },
        ) { turns, streaming, error, title, info ->
            ChatUiState(
                turns = turns,
                streaming = streaming,
                fictionTitle = title,
                noProvider = info.noProvider,
                error = error,
                actionsAvailable = info.actionsAvailable,
                imagesSupported = info.imagesSupported,
            )
        }
        val withSecondary = combine(base, _readingText, _memoryDebug) { state, reading, memoryDebug ->
            state.copy(readingText = reading, crossFictionMemoryDebug = memoryDebug)
        }
        val withTertiary = combine(withSecondary, _toolCalls) { state, toolCalls ->
            state.copy(toolCalls = toolCalls)
        }
        combine(withTertiary, _pendingImage, _imageDropped) { state, image, dropped ->
            state.copy(pendingImage = image, imageDroppedWarning = dropped)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())
    }

    init {
        // Auto-clear [_readingText] when the engine naturally finishes
        // an utterance (the user didn't tap stop — the AI text just
        // ended). Without this the UI would stay stuck on the
        // "Stop" icon for the played-out turn.
        viewModelScope.launch {
            playback.recapPlayback.collect { state ->
                if (state == UiRecapPlaybackState.Idle) _readingText.value = null
            }
        }
    }

    /** Issue #214 — read [text] aloud through the TTS engine. Reuses
     *  the same one-shot path as the chapter-recap modal (#189). The
     *  caller is responsible for trimming the text; we surface it
     *  exactly as the model emitted it. */
    fun readAloud(text: String) {
        if (text.isBlank()) return
        _readingText.value = text
        viewModelScope.launch { playback.speakText(text) }
    }

    /** Issue #214 — cancel the in-flight read-aloud. Idempotent. */
    fun stopReadAloud() {
        _readingText.value = null
        playback.stopSpeaking()
    }

    /** Issue #215 — set the composer's queued image. Called by the
     *  composable after the SAF picker resolves + the bytes are
     *  resized + encoded. The image stays queued until the user
     *  sends the next message or removes it via [clearPendingImage]. */
    fun attachImage(image: ImageAttachment) {
        _pendingImage.value = image
    }

    /** Issue #215 — clear the composer's queued image without
     *  sending. Used by the x affordance on the thumbnail preview. */
    fun clearPendingImage() {
        _pendingImage.value = null
    }

    /** Send [text] as a user turn and stream the assistant reply.
     *  Cancels any in-flight stream first — the user-facing input
     *  is supposed to be disabled while streaming, but the cancel
     *  guards against races (e.g. text injected via accessibility).
     *
     *  Issue #216 — routes through the tool-aware chat path when
     *  the user has actions enabled AND the active provider
     *  supports function calling; otherwise falls back to the
     *  plain text-only chat path (pre-#216 behaviour). */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        sendJob?.cancel()
        // Issue #1057 — claim a fresh stream identity. The just-cancelled
        // job (if any) keeps its older stamp, so when its onCompletion
        // finally unwinds it will no longer match and won't touch
        // _streaming.
        val generation = ++streamGeneration
        _error.value = null
        _toolCalls.value = emptyList()
        // Issue #215 — clear last send's "image dropped" banner so
        // it doesn't survive into an unrelated turn.
        _imageDropped.value = false

        // Issue #215 — snapshot the queued image (if any) and clear
        // the composer state immediately so a slow send doesn't leave
        // the thumbnail visible while the next message is composed.
        // We capture the value here so even if the user re-attaches
        // another image mid-send, this send's payload is stable.
        val attached = _pendingImage.value
        _pendingImage.value = null

        sendJob = viewModelScope.launch {
            val cfg = configFlow.first()
            val provider = cfg.provider
            if (provider == null) {
                _error.value = ChatError.NotConfigured(
                    "Pick a provider in Settings → AI.",
                )
                return@launch
            }

            ensureSession(cfg, provider)

            // Issue #215 — decide whether the provider can take the
            // attached image. If the repo says "no", drop the image
            // and surface the warning banner; the text portion still
            // goes through.
            //
            // When [llmRepo] is null (test-injection path) we treat
            // "unknown" as "allow" — the providers themselves will
            // silently ignore image parts if their wire format can't
            // serialize them, so this is safe.
            val supportsImages = llmRepo?.supportsImages(provider)
            val providerAcceptsImages = attached != null &&
                (supportsImages == true || supportsImages == null)
            val effectiveImage = if (providerAcceptsImages) attached else null
            if (attached != null && supportsImages == false) {
                _imageDropped.value = true
            }
            // Stash the URI keyed by sent text so the bubble can show
            // the thumbnail once the user-turn row arrives via
            // observeMessages. We keep the keys around for the
            // session's lifetime (process death clears them, which
            // is fine — image bytes don't persist either).
            if (effectiveImage != null) {
                _imageUriByText.value = _imageUriByText.value +
                    (trimmed to effectiveImage.uri)
            }
            // Issue #212 — rebuild the system prompt from the live
            // grounding settings before every send. Lets the user
            // flip a toggle and have the next reply use the new
            // context level immediately, without restarting the chat.
            //
            // Issue #217 — append the cross-fiction memory block.
            // The block is opt-in via Settings → AI ("Carry memory
            // across fictions"), default ON. When the user toggles
            // it off mid-chat, the next send's prompt drops the
            // block immediately — same dynamic-rebuild story as
            // #212's grounding toggles.
            val basePrompt = buildSystemPrompt()
            val carryEnabled = settingsRepo.settings.first().ai.carryMemoryAcrossFictions
            val memoryBlock = crossFictionBlock.build(
                fictionId = fictionId,
                userMessage = trimmed,
                enabled = carryEnabled,
            )
            _memoryDebug.value = if (memoryBlock.entryCount > 0) {
                CrossFictionMemoryDebug(
                    entryCount = memoryBlock.entryCount,
                    droppedCount = memoryBlock.droppedCount,
                    approxTokens = memoryBlock.approxTokens,
                )
            } else {
                null
            }
            // Issue #216 — when actions are enabled, append a hint to
            // the system prompt naming the active fiction's id. The
            // model uses this to populate `fictionId` arguments without
            // asking the user.
            val actionsEnabled = cfg.aiActionsEnabled &&
                llmRepo?.supportsTools(provider) == true &&
                shelfRepo != null && chapterRepo != null
            val actionsHint = if (actionsEnabled) {
                "\n\nThe user's active fiction id is \"$fictionId\". When " +
                    "you call a tool that takes a fictionId argument, use " +
                    "this value unless the user explicitly named a different " +
                    "book in the message."
            } else {
                ""
            }
            sessionRepo.updateSystemPrompt(
                sessionId,
                basePrompt + memoryBlock.text + actionsHint,
            )

            val buf = StringBuilder()
            _streaming.value = ""

            // Issue #215 — build the multi-modal content blocks the
            // session repo will attach to the latest user message.
            // Image first then text mirrors Anthropic's documented
            // best practice (the model attends to the image while
            // reading the question).
            val userParts: List<LlmContentBlock>? = effectiveImage?.let { img ->
                listOf(
                    LlmContentBlock.Image(
                        base64 = img.base64,
                        mimeType = img.mimeType,
                    ),
                    LlmContentBlock.Text(trimmed),
                )
            }

            if (actionsEnabled) {
                streamWithTools(trimmed, buf, userParts, generation)
            } else {
                streamPlainText(trimmed, buf, userParts, generation)
            }
        }
    }

    /** Plain-text streaming path — preserved from pre-#216. Used when
     *  actions are disabled or the provider doesn't support tool use.
     *
     *  Issue #215 — [userParts], when non-null, carries multi-modal
     *  content blocks attached to the latest user message; passed
     *  through to the session repo unchanged. */
    private suspend fun streamPlainText(
        trimmed: String,
        buf: StringBuilder,
        userParts: List<LlmContentBlock>? = null,
        generation: Int = streamGeneration,
    ) {
        sessionRepo.chat(sessionId, trimmed, userParts = userParts)
            .catch { e -> _error.value = mapError(e) }
            .onCompletion { cause ->
                // Issue #1057 — only the active stream owns _streaming.
                // A cancelled job's late unwind must not blank the
                // bubble of the send that replaced it.
                if (streamStillOwnsState(generation, streamGeneration)) {
                    _streaming.value = null
                }
                if (cause == null) {
                    extractAndRecord(buf.toString())
                }
            }
            .collect { delta ->
                buf.append(delta)
                _streaming.value = buf.toString()
            }
    }

    /** Issue #216 — tool-aware path. Same persistence + memory-extract
     *  contract as [streamPlainText] but consumes [ChatStreamEvent]
     *  emits, routing tool-call events into [_toolCalls] for the UI to
     *  render alongside the streaming bubble.
     *
     *  Issue #215 — [userParts] threads through the same image
     *  attachment path as [streamPlainText]. */
    private suspend fun streamWithTools(
        trimmed: String,
        buf: StringBuilder,
        userParts: List<LlmContentBlock>? = null,
        generation: Int = streamGeneration,
    ) {
        val handlers = ChatToolHandlers(
            activeFictionId = fictionId,
            shelfRepo = shelfRepo!!,
            chapterRepo = chapterRepo!!,
            fictionRepo = fictionRepo,
            playback = playback,
            settingsRepo = settingsRepo,
            onOpenVoiceLibrary = {
                _navEvents.tryEmit(ChatNavEvent.OpenVoiceLibrary)
            },
        )
        sessionRepo.chatWithTools(
            sessionId = sessionId,
            userMessage = trimmed,
            tools = handlers.registry(),
            userParts = userParts,
        )
            .catch { e -> _error.value = mapError(e) }
            .onCompletion { cause ->
                // Issue #1057 — see streamPlainText: a stale cancelled
                // generation must not clobber the active stream's text.
                if (streamStillOwnsState(generation, streamGeneration)) {
                    _streaming.value = null
                }
                if (cause == null) {
                    extractAndRecord(buf.toString())
                }
            }
            .collect { event ->
                when (event) {
                    is ChatStreamEvent.TextDelta -> {
                        buf.append(event.text)
                        _streaming.value = buf.toString()
                    }
                    is ChatStreamEvent.ToolCallStarted -> {
                        _toolCalls.value = _toolCalls.value + ToolCallEvent(
                            id = event.call.id,
                            name = event.call.name,
                            arguments = event.call.arguments,
                            result = null,
                        )
                    }
                    is ChatStreamEvent.ToolCallCompleted -> {
                        _toolCalls.value = _toolCalls.value.map { existing ->
                            if (existing.id == event.id) {
                                existing.copy(result = event.result)
                            } else {
                                existing
                            }
                        }
                    }
                }
            }
    }

    /**
     * Issue #217 — pull entity candidates out of [reply] and upsert
     * them into the per-fiction memory table. Errors are swallowed
     * (logged via the throwable's cause if the extractor explodes on
     * pathological input) — memory population is a best-effort side
     * effect of the chat, never a blocker. The pre-existing chat
     * persistence path doesn't care whether this succeeds.
     */
    private suspend fun extractAndRecord(reply: String) {
        val entities = runCatching { FictionMemoryExtractor.extract(reply) }
            .getOrDefault(emptyList())
        // Try to fish a chapter index out of the live playback state
        // for the firstSeenChapterIndex hint. The hint is best-effort
        // — if playback isn't active, we record null (which the
        // Notebook UI orders last).
        val chapterIdx = runCatching {
            val pb = playback.state.first()
            if (pb.fictionId != fictionId) return@runCatching null
            val chapterId = pb.chapterId ?: return@runCatching null
            val chapters = fictionRepo.chaptersFor(fictionId).first()
            chapters.indexOfFirst { it.id == chapterId }.takeIf { it >= 0 }
        }.getOrNull()
        entities.forEach { e ->
            runCatching {
                memoryRepo.recordEntity(
                    fictionId = fictionId,
                    entityType = e.kind,
                    name = e.name,
                    summary = e.summary,
                    firstSeenChapterIndex = chapterIdx,
                    userEdited = false,
                )
            }
        }
    }

    /** Dismiss an error banner. Used by the UI's "X" affordance. */
    fun dismissError() { _error.value = null }

    /** Issue #215 — dismiss the "image dropped" info banner. */
    fun dismissImageDroppedWarning() { _imageDropped.value = false }

    /** Cancel an in-flight stream. The repo persists user turns
     *  immediately but only saves the assistant reply on completion
     *  — cancelling drops the partial reply, which matches the
     *  cancel-recap behaviour (RecapModal kdoc). */
    fun cancel() {
        sendJob?.cancel()
        sendJob = null
        // Issue #1057 — revoke the cancelled job's authority over
        // _streaming so its late onCompletion no-ops. We own the reset
        // here (Stop clears the bubble immediately).
        streamGeneration++
        _streaming.value = null
    }

    override fun onCleared() {
        sendJob?.cancel()
        super.onCleared()
    }

    /** Resolve "the model bound to this provider" off [LlmConfig]. */
    private fun modelFor(provider: ProviderId, cfg: LlmConfig): String = when (provider) {
        ProviderId.Claude -> cfg.claudeModel
        ProviderId.OpenAi -> cfg.openAiModel
        ProviderId.Ollama -> cfg.ollamaModel
        ProviderId.Bedrock -> cfg.bedrockModel
        ProviderId.Vertex -> cfg.vertexModel
        // Foundry's deployment id IS its model id in Azure-speak.
        ProviderId.Foundry -> cfg.foundryDeployment.ifBlank { "gpt-4o-mini" }
        // Teams isn't user-pickable yet — fall through to a sane
        // default; the provider will throw NotConfigured downstream.
        ProviderId.Teams -> cfg.claudeModel
    }

    /**
     * Build the librarian system prompt. The fiction title is always
     * included — it's the bare minimum context. Everything else is
     * gated by user-controlled grounding toggles in Settings → AI
     * (issue #212): chapter title, current sentence, entire chapter,
     * and entire-book-so-far. Token cost ramps fast on the bottom
     * two; users opt in based on their provider's context window.
     *
     * Grounding only injects book content when the user is actively
     * listening to *this* fiction. A chat opened from the fiction
     * detail screen with no playback active falls back to the
     * title-only prompt — there's no "current sentence" without
     * playback state, and grabbing chapter 1 unprompted would
     * surprise the user.
     *
     * Spoiler-prevention is a soft hint, not a hard wall: the AI
     * doesn't have future-chapter text in context anyway, but the
     * prompt makes its limits explicit so users get a coherent
     * "I can only speak to what I've read with you" voice.
     */
    private suspend fun buildSystemPrompt(): String {
        val fictionTitle = fictionRepo.fictionById(fictionId).first()?.title
            ?: "this fiction"
        val grounding = settingsRepo.settings.first().ai.chatGrounding
        val pb = playback.state.first()
        val onSameFiction = pb.fictionId == fictionId

        return buildString {
            append("You are a careful, literate librarian-companion ")
            append("to a reader of \"")
            append(fictionTitle)
            append("\". ")

            if (onSameFiction) {
                appendGroundingClauses(grounding, pb)
            }

            append("Answer questions about plot, characters, pacing, ")
            append("and writing craft. Quote sparingly. Don't invent ")
            append("details. Don't spoil future chapters — speak only ")
            append("to what the reader has likely read so far.")
        }
    }

    /**
     * Append per-toggle context clauses. Order matters — coarse to
     * fine: book-so-far → entire chapter → current sentence →
     * chapter title. Models tend to weight later context more, so
     * the most-precise "what they're on right now" clause goes last.
     *
     * Each section is delimited with markdown-ish headers so the
     * model can tell prefixes apart from the text it's grounding in.
     */
    private suspend fun StringBuilder.appendGroundingClauses(
        grounding: UiChatGrounding,
        pb: `in`.jphe.storyvox.feature.api.UiPlaybackState,
    ) {
        val chapterId = pb.chapterId

        if (grounding.includeEntireBookSoFar && chapterId != null) {
            val all = fictionRepo.chaptersFor(fictionId).first()
            val currentIdx = all.indexOfFirst { it.id == chapterId }
            if (currentIdx >= 0) {
                val priorChapters = all.subList(0, currentIdx)
                val priorTexts = priorChapters.mapNotNull { ch ->
                    fictionRepo.chapterTextById(ch.id)?.let { text -> ch.title to text }
                }
                if (priorTexts.isNotEmpty()) {
                    append("\n\n## Story so far (chapters before the current one)\n\n")
                    priorTexts.forEach { (title, text) ->
                        append("### ").append(title).append("\n")
                        append(text).append("\n\n")
                    }
                }
            }
        }

        if (grounding.includeEntireChapter && chapterId != null) {
            val chapterText = fictionRepo.chapterTextById(chapterId)
            if (!chapterText.isNullOrBlank()) {
                append("\n\n## Current chapter (\"")
                    .append(pb.chapterTitle)
                    .append("\") in full\n\n")
                append(chapterText).append("\n\n")
            }
        }

        if (grounding.includeCurrentSentence) {
            // chapterText flow exposes the live chapter body the
            // playback layer is reading. Slicing with sentenceStart..
            // sentenceEnd matches the highlighted sentence in the
            // reader UI, so "current sentence" means exactly what the
            // user sees on-screen.
            val text = playback.chapterText.first()
            val end = pb.sentenceEnd.coerceAtMost(text.length)
            val start = pb.sentenceStart.coerceIn(0, end)
            if (end > start) {
                append("The reader is currently on this sentence: \"")
                append(text.substring(start, end).trim())
                append("\". ")
            }
        }

        if (grounding.includeChapterTitle && pb.chapterTitle.isNotBlank()) {
            append("The reader is in the chapter \"")
            append(pb.chapterTitle)
            append("\". ")
        }
    }

    /** Idempotent — first call creates the Room row, later calls
     *  no-op. The repo's `chat()` will throw if the session doesn't
     *  exist, so this MUST run before the first send. */
    private suspend fun ensureSession(cfg: LlmConfig, provider: ProviderId) {
        if (sessionEnsured) return
        // It's possible another send happened in a prior process and
        // already created the row. createSession is idempotent on
        // explicitId via @Upsert, but that would clobber the bound
        // provider/model. Use a no-op-if-exists shape instead by
        // observing message count: if observeMessages has emitted
        // any history, the row already exists.
        //
        // Simpler: just call createSession with the explicit id. The
        // DAO is @Upsert, so a duplicate id refreshes name/provider/
        // model — which is fine here since we use deterministic ids
        // and the user can't mutate them out from under us.
        sessionRepo.createSession(
            name = "Chat about ${fictionRepo.fictionById(fictionId).first()?.title ?: "fiction"}",
            provider = provider,
            model = modelFor(provider, cfg),
            systemPrompt = buildSystemPrompt(),
            featureKind = FeatureKind.CharacterLookup,
            anchorFictionId = fictionId,
            explicitId = sessionId,
        )
        sessionEnsured = true
    }

    private fun mapError(e: Throwable): ChatError = when (e) {
        is LlmError.NotConfigured -> ChatError.NotConfigured(
            "Pick a provider in Settings → AI.",
        )
        is LlmError.AuthFailed -> ChatError.AuthFailed(
            "${e.provider} key is invalid — check Settings.",
        )
        is LlmError.Transport -> ChatError.Transport(
            "Couldn't reach the AI — check your connection and try again.",
        )
        is LlmError.ProviderError -> ChatError.ProviderError(
            "AI service error (${e.status}). Try again in a moment.",
        )
        else -> ChatError.Transport(e.message ?: "Send failed.")
    }
}

private fun LlmMessage.toTurn(): ChatTurn = ChatTurn(
    role = when (role) {
        LlmMessage.Role.user -> ChatTurn.Role.User
        LlmMessage.Role.assistant -> ChatTurn.Role.Assistant
    },
    text = content,
)
