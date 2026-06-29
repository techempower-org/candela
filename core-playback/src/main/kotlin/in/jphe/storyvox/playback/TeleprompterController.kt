package `in`.jphe.storyvox.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #1308 — single source of truth for the teleprompter's user-facing
 * controls (#1239 / #1306), hoisted out of `ReaderView`'s local Compose state so
 * **both** the phone reader and the Wear bridge can drive one shared state.
 *
 * The teleprompter shipped (#1239) with `enabled` / `playing` / `wpm` held as
 * `var … by remember` inside the reader composition — reachable only from the
 * phone UI. The Wear teleprompter remote (#1308 PR2) needs to drive those same
 * controls from `PhoneWearBridge` (core-playback), which can't see Compose
 * state. Lifting them here — a `@Singleton`, exactly like [PlaybackController]
 * that `PhoneWearBridge` already drives — gives both surfaces one source of
 * truth.
 *
 * ## Scope
 * Only the three **remote-able controls** live here. The teleprompter's *mode*
 * (auto-scroll vs practice, #1306) and its practice-flow internals
 * (`practiceTurn` / `practiceResumeFrom`) stay local to the reader — they're
 * playhead/scroll-derived, not wrist controls, and a watch v1 only needs
 * play / pause / pace.
 *
 * ## Lifecycle
 * Being a `@Singleton`, this outlives any single reader visit. To preserve the
 * #1239 "transient per session" phone behavior, the reader resets [setEnabled]
 * and [setPlaying] to `false` when it leaves composition (a singleton retaining
 * `enabled = true` across navigation would otherwise be a behavior change).
 *
 * ## WPM
 * [wpm] is the **live** session pace. Persistence (#1287/#1304, via
 * `SettingsRepositoryUi`) is bridged in `ReaderViewModel` — this module can't
 * reach the settings layer. Clamping and the step math stay in the reader's
 * pure helpers (`adjustTeleprompterWpm`); this holder stores whatever pace it's
 * given.
 */
@Singleton
class TeleprompterController @Inject constructor() {

    private val _enabled = MutableStateFlow(false)

    /** True when the teleprompter transport has replaced the normal reader
     *  chrome. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _playing = MutableStateFlow(false)

    /** True while the auto-scroll is running (the rehearsal is "playing"). */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _wpm = MutableStateFlow(DEFAULT_WPM)

    /** The live rehearsal pace in words-per-minute. Seeded from the persisted
     *  pref (on enable) and written through to it on change — both bridged by
     *  `ReaderViewModel`. */
    val wpm: StateFlow<Int> = _wpm.asStateFlow()

    fun setEnabled(enabled: Boolean) { _enabled.value = enabled }

    fun setPlaying(playing: Boolean) { _playing.value = playing }

    fun setWpm(wpm: Int) { _wpm.value = wpm }

    private companion object {
        /**
         * Pre-seed default, mirroring `TELEPROMPTER_DEFAULT_WPM` in
         * `:feature` (#1239). Duplicated as a literal because core-playback
         * can't depend on `:feature`; it's only the value shown before the
         * reader seeds the persisted pace on enable (#1287/#1304), so the two
         * never meaningfully diverge.
         */
        const val DEFAULT_WPM = 130
    }
}
