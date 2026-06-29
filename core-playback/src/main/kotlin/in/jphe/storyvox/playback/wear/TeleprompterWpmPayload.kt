package `in`.jphe.storyvox.playback.wear

import java.nio.ByteBuffer

/**
 * Issue #1308 — wire format for the wear↔phone `CMD_TELEPROMPTER_WPM` message
 * (voice-paced/manual teleprompter remote from the wrist).
 *
 * Mirrors [SeekPayload]: a shared, single-source encode/decode so the watch
 * ([in.jphe.storyvox.wear.playback.WearPlaybackBridge], encode) and phone
 * ([PhoneWearBridge], decode) can never drift. The payload is an **absolute
 * words-per-minute** value — the watch owns the current WPM (synced from the
 * phone's `TeleprompterController.wpm`) and sends the new absolute, the same
 * way [SeekPayload] sends an absolute position rather than a delta.
 *
 * Encoding is a fixed 4-byte big-endian Int. The fixed width doubles as a
 * validation check: anything that isn't exactly 4 bytes (including the `null`
 * MessageClient delivers for payload-less CMD_* messages) decodes to `null`,
 * which the phone bridge ignores rather than crashes on.
 *
 * [clamp]/[step] are watch-side helpers (the `wpm ±` stepper + rotary input
 * compute the next value through them); [encode]/[decode] transport the value
 * faithfully and leave range-enforcement to the helpers + the controller.
 */
object TeleprompterWpmPayload {

    /** Slowest / fastest WPM the remote will request. The phone's
     *  `TeleprompterController.setWpm` is the authority; this just keeps the
     *  wrist UI from sending absurd values. */
    const val MIN_WPM: Int = 60
    const val MAX_WPM: Int = 400

    /** Default increment for one `−` / `+` tap or rotary detent. */
    const val DEFAULT_STEP_WPM: Int = 10

    private const val SIZE_BYTES = 4

    /** Pack [wpm] into a 4-byte big-endian payload. */
    fun encode(wpm: Int): ByteArray =
        ByteBuffer.allocate(SIZE_BYTES).putInt(wpm).array()

    /**
     * Decode a 4-byte big-endian payload back to a WPM, or `null` if the
     * payload is absent or malformed (wrong length).
     */
    fun decode(payload: ByteArray?): Int? {
        if (payload == null || payload.size != SIZE_BYTES) return null
        return ByteBuffer.wrap(payload).int
    }

    /** Clamp a WPM to the [MIN_WPM]..[MAX_WPM] rails. */
    fun clamp(wpm: Int): Int = wpm.coerceIn(MIN_WPM, MAX_WPM)

    /**
     * Next WPM after nudging [current] by [deltaSteps] detents (negative =
     * slower), clamped to the rails. The watch sends the result via [encode].
     */
    fun step(current: Int, deltaSteps: Int, stepWpm: Int = DEFAULT_STEP_WPM): Int =
        clamp(current + deltaSteps * stepWpm)
}
