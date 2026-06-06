package `in`.jphe.storyvox.playback.wear

import java.nio.ByteBuffer

/**
 * Issue #1031 — wire format for the wear↔phone `CMD_SEEK` message.
 *
 * Shared by both bridges so the encode (watch, [in.jphe.storyvox.wear.playback.WearPlaybackBridge])
 * and decode (phone, [PhoneWearBridge]) sides can never drift apart. The
 * payload is an absolute position in **milliseconds on the media-time axis** —
 * the same currency the seekbar-tap path already speaks via
 * [in.jphe.storyvox.playback.PlaybackController.seekToPositionMs]. The watch
 * owns the fraction→ms conversion (it has `durationEstimateMs` in its synced
 * [in.jphe.storyvox.playback.PlaybackState]); the phone just seeks.
 *
 * Encoding is a fixed 8-byte big-endian Long. The fixed width doubles as a
 * validation check: anything that isn't exactly 8 bytes (including the `null`
 * MessageClient delivers for the payload-less CMD_* messages) decodes to
 * `null`, which the phone bridge ignores rather than crashes on.
 */
object SeekPayload {

    private const val SIZE_BYTES = 8

    /** Pack [positionMs] into an 8-byte big-endian payload. */
    fun encode(positionMs: Long): ByteArray =
        ByteBuffer.allocate(SIZE_BYTES).putLong(positionMs).array()

    /**
     * Decode an 8-byte big-endian payload back to a position in ms, or `null`
     * if the payload is absent or malformed (wrong length).
     */
    fun decode(payload: ByteArray?): Long? {
        if (payload == null || payload.size != SIZE_BYTES) return null
        return ByteBuffer.wrap(payload).long
    }

    /**
     * Convert a 0..1 ring [fraction] to an absolute position in ms against the
     * chapter's [durationMs]. Fractions are clamped; an unknown duration
     * (`<= 0`, before the first state sync) targets position 0 rather than
     * dividing by a zero rail.
     */
    fun fromFraction(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val clamped = fraction.coerceIn(0f, 1f)
        return (clamped * durationMs).toLong()
    }
}
