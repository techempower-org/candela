package `in`.jphe.storyvox.playback.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1031 — the watch's circular scrubber sends a CMD_SEEK message with a
 * target position payload; the phone decodes it. [SeekPayload] is the shared
 * wire format so the encode (watch) and decode (phone) sides can never drift.
 *
 * The payload is an absolute position in milliseconds on the media-time axis
 * (mirrors the seekbar-tap path which already thinks in ms — see
 * [in.jphe.storyvox.playback.PlaybackController.seekToPositionMs]).
 */
class SeekPayloadTest {

    @Test fun `round-trips a positive position`() {
        val bytes = SeekPayload.encode(123_456L)
        assertEquals(123_456L, SeekPayload.decode(bytes))
    }

    @Test fun `round-trips zero`() {
        assertEquals(0L, SeekPayload.decode(SeekPayload.encode(0L)))
    }

    @Test fun `round-trips a large position past Int range`() {
        // ~25 days in ms — comfortably past Int.MAX_VALUE, proving the wire
        // format carries a full 64-bit Long, not a truncated Int.
        val big = 2_200_000_000L
        assertEquals(big, SeekPayload.decode(SeekPayload.encode(big)))
    }

    @Test fun `encodes to exactly 8 bytes (big-endian Long)`() {
        assertEquals(8, SeekPayload.encode(1L).size)
    }

    @Test fun `decode returns null for null payload`() {
        // MessageClient delivers null when a message carries no data (every
        // existing CMD_* sends null). A stray null on the seek path must not
        // crash the phone bridge — it's simply ignored.
        assertNull(SeekPayload.decode(null))
    }

    @Test fun `decode returns null for wrong-length payload`() {
        assertNull(SeekPayload.decode(byteArrayOf(1, 2, 3)))
        assertNull(SeekPayload.decode(ByteArray(0)))
    }

    @Test fun `fromFraction maps mid-ring to half the duration`() {
        // The ring reports a 0..1 fraction; the watch converts to ms using the
        // synced durationEstimateMs before sending.
        assertEquals(300_000L, SeekPayload.fromFraction(0.5f, durationMs = 600_000L))
    }

    @Test fun `fromFraction clamps out-of-range fractions`() {
        assertEquals(0L, SeekPayload.fromFraction(-0.2f, durationMs = 600_000L))
        assertEquals(600_000L, SeekPayload.fromFraction(1.4f, durationMs = 600_000L))
    }

    @Test fun `fromFraction is zero when duration unknown`() {
        // Before the first state sync the watch has durationEstimateMs == 0;
        // a scrub then targets position 0 rather than dividing by zero.
        assertEquals(0L, SeekPayload.fromFraction(0.5f, durationMs = 0L))
    }
}
