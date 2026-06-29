package `in`.jphe.storyvox.playback.tts

import `in`.jphe.storyvox.playback.voice.EngineType

/**
 * Issue #1383 — pure decision functions guarding the two synthesis-pipeline
 * rebuild paths that caused the "Piper synthesis cancelled immediately after a
 * PCM cache MISS → intermittent fragments" bug. Extracted as pure functions
 * (no Android, no coroutines) so the *policy* is unit-testable without standing
 * up the full [EnginePlayer] + Hilt graph + sherpa-onnx AARs — the same
 * pattern the engine already uses for [shouldAutoPlayAfterAdvance],
 * [shouldCheckpointPosition], `crossedThermalModerateBoundary`, and
 * `DefaultPlaybackController.shouldWatchdogFireFallbackAdvance`.
 *
 * These encode the *contract* the #1383 fix must uphold; the fix wires
 * `loadAndPlay` / the voice-event path through them (a one-line call each) so
 * the regression net guards the real path rather than a parallel copy. Until
 * then they stand as the executable specification of the invariant.
 *
 * The on-device repro (R5CRB0W66MK, v1.x) logged:
 * ```
 * pcm-cache MISS chapter=3609400 voice=piper... fromSentence=11
 * serial producer: cancelled (close/seek/voice swap) — silent exit
 * pcm-cache MISS chapter=3609400 voice=piper... fromSentence=11   ← 54 ms later
 * ```
 * Two MISSes for the same chapter+sentence 54 ms apart = a re-entrant pipeline
 * rebuild. The two guards below close the two doors that let that happen.
 */

/**
 * Issue #1383 / #944 / #956 — should a second, concurrent `loadAndPlay` for the
 * same chapter be **deduplicated** (skipped) rather than tearing down and
 * rebuilding the pipeline?
 *
 * `true` when the engine is *already on* the requested `(fictionId, chapterId)`
 * with a **live pipeline**: a near-simultaneous re-trigger (the double-fire
 * behind the "two MISSes 54 ms apart" log) must be a no-op, not a second
 * teardown+rebuild that cancels the first producer mid-synth and strands the
 * listener on fragments. This is the "never two pipelines for the same chapter"
 * invariant the `loadAndPlayMutex` exists to enforce.
 *
 * When no pipeline is running yet (cold start), or the request targets a
 * *different* chapter (a genuine navigation / advance), it returns `false` so
 * the real load proceeds.
 */
internal fun shouldDedupeConcurrentLoad(
    armedFictionId: String?,
    armedChapterId: String?,
    requestedFictionId: String,
    requestedChapterId: String,
    pipelineRunning: Boolean,
): Boolean =
    pipelineRunning &&
        armedFictionId == requestedFictionId &&
        armedChapterId == requestedChapterId

/**
 * Issue #1383 — should a **system-TTS framework reconnection** event trigger a
 * playback-pipeline rebuild?
 *
 * Only when the *currently active* voice is actually an [EngineType.SystemTts]
 * voice (which is driven by the framework `TextToSpeech` instance that just
 * reconnected). When an on-device neural engine is active — Piper, Kokoro,
 * Kitten, Supertonic — or a cloud engine (Azure), the framework reconnect is
 * irrelevant: those render through their own sherpa-onnx / HTTP paths, not the
 * system `TextToSpeech`. Rebuilding the pipeline for them is the spurious
 * teardown that cancelled the Piper producer mid-MISS in #1383.
 *
 * So: `true` iff [activeEngineType] is [EngineType.SystemTts]; `false` for
 * every neural / cloud engine. The voice-change propagation path gates on this
 * instead of rebuilding unconditionally.
 */
internal fun shouldRebuildForSystemTtsReconnect(activeEngineType: EngineType): Boolean =
    activeEngineType is EngineType.SystemTts
