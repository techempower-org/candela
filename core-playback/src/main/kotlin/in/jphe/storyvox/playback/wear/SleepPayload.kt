package `in`.jphe.storyvox.playback.wear

import `in`.jphe.storyvox.playback.SleepTimerMode
import java.nio.ByteBuffer

/**
 * Wire format for the wear↔phone `CMD_SLEEP_SET` message (set a sleep timer
 * from the wrist).
 *
 * Mirrors [TeleprompterWpmPayload] / [SeekPayload]: one shared encode/decode so
 * the watch ([in.jphe.storyvox.wear.playback.WearPlaybackBridge], encode) and
 * phone ([PhoneWearBridge], decode) can't drift. A fixed 4-byte big-endian Int
 * carries the chosen option:
 *  - a positive value = [SleepTimerMode.Duration] in minutes (15 / 30 / 45),
 *  - the sentinel `0` = [SleepTimerMode.EndOfChapter].
 *
 * The fixed width doubles as validation: anything not exactly 4 bytes
 * (including the `null` MessageClient delivers for payload-less messages) or a
 * negative value decodes to `null`, which the phone bridge ignores rather than
 * crashing on. Cancelling is the separate payload-less `CMD_SLEEP_CANCEL`, so
 * this only ever encodes an arm request.
 */
object SleepPayload {

    private const val SIZE_BYTES = 4
    private const val END_OF_CHAPTER = 0

    /** Duration presets the wrist offers (minutes). End-of-chapter is the
     *  non-duration option, encoded via the [END_OF_CHAPTER] sentinel. */
    val DURATION_PRESETS_MIN: List<Int> = listOf(15, 30, 45)

    /** Pack a sleep [mode] into a 4-byte big-endian payload. */
    fun encode(mode: SleepTimerMode): ByteArray {
        val v = when (mode) {
            is SleepTimerMode.Duration -> mode.minutes
            SleepTimerMode.EndOfChapter -> END_OF_CHAPTER
        }
        return ByteBuffer.allocate(SIZE_BYTES).putInt(v).array()
    }

    /**
     * Decode a 4-byte payload back to a [SleepTimerMode], or `null` if it's
     * absent, the wrong length, or a negative (malformed) value.
     */
    fun decode(payload: ByteArray?): SleepTimerMode? {
        if (payload == null || payload.size != SIZE_BYTES) return null
        return when (val v = ByteBuffer.wrap(payload).int) {
            END_OF_CHAPTER -> SleepTimerMode.EndOfChapter
            in 1..Int.MAX_VALUE -> SleepTimerMode.Duration(v)
            else -> null
        }
    }
}
