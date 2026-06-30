package `in`.jphe.storyvox.playback.wear

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Wire format for the wear↔phone `CMD_SET_SPEED` message (per-fiction
 * playback-speed remote from the wrist).
 *
 * Mirrors [TeleprompterWpmPayload] / [SeekPayload]: a shared, single-source
 * encode/decode so the watch ([in.jphe.storyvox.wear.playback.WearPlaybackBridge],
 * encode) and phone ([PhoneWearBridge], decode) can never drift. The payload is
 * an **absolute speed multiplier** — the watch owns the current speed (synced
 * from the phone's `PlaybackState.speed`) and sends the new absolute, the same
 * way [SeekPayload] sends an absolute position rather than a delta.
 *
 * Encoding is a fixed 4-byte big-endian Float. The fixed width doubles as a
 * validation check: anything that isn't exactly 4 bytes (including the `null`
 * MessageClient delivers for payload-less CMD_* messages) decodes to `null`,
 * which the phone bridge ignores rather than crashes on.
 *
 * [clamp]/[step] are watch-side helpers (the −/+ stepper computes the next value
 * through them); [encode]/[decode] transport the value faithfully and leave
 * range-enforcement to the helpers + `PlaybackController.setSpeed` (which also
 * coerces to 0.5..3.0).
 */
object SpeedPayload {

    /** Slowest / fastest multiplier the remote will request — matches
     *  `PlaybackController.setSpeed`'s `coerceIn(0.5f, 3.0f)`. */
    const val MIN_SPEED: Float = 0.5f
    const val MAX_SPEED: Float = 3.0f

    /** Default increment for one `−` / `+` tap or rotary detent. */
    const val DEFAULT_STEP: Float = 0.1f

    private const val SIZE_BYTES = 4

    /** Pack [speed] into a 4-byte big-endian payload. */
    fun encode(speed: Float): ByteArray =
        ByteBuffer.allocate(SIZE_BYTES).putFloat(speed).array()

    /**
     * Decode a 4-byte big-endian payload back to a speed, or `null` if the
     * payload is absent or malformed (wrong length).
     */
    fun decode(payload: ByteArray?): Float? {
        if (payload == null || payload.size != SIZE_BYTES) return null
        return ByteBuffer.wrap(payload).float
    }

    /**
     * Clamp a speed to the [MIN_SPEED]..[MAX_SPEED] rails, snapped to the 0.1
     * grid so float drift never surfaces a "1.4000001×" on the wrist.
     */
    fun clamp(speed: Float): Float = snap(speed).coerceIn(MIN_SPEED, MAX_SPEED)

    /**
     * Next speed after nudging [current] by [deltaSteps] detents (negative =
     * slower), clamped to the rails. The watch sends the result via [encode].
     */
    fun step(current: Float, deltaSteps: Int, step: Float = DEFAULT_STEP): Float =
        clamp(current + deltaSteps * step)

    /** Snap to the nearest 0.1 to keep displayed/persisted values on the grid. */
    private fun snap(v: Float): Float = (v * 10f).roundToInt() / 10f
}
