package `in`.jphe.storyvox.playback.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1308 — shared wire format for the teleprompter-WPM remote command,
 * so the watch (encode) and phone (decode) sides can't drift. Mirrors
 * [SeekPayloadTest]; WPM is an absolute 4-byte big-endian Int.
 */
class TeleprompterWpmPayloadTest {

    @Test fun `round-trips a wpm`() {
        assertEquals(140, TeleprompterWpmPayload.decode(TeleprompterWpmPayload.encode(140)))
    }

    @Test fun `encodes to exactly 4 bytes (big-endian Int)`() {
        assertEquals(4, TeleprompterWpmPayload.encode(140).size)
    }

    @Test fun `the wire transports the value faithfully, without clamping`() {
        // encode/decode are pure transport; range-enforcement is clamp()/the
        // controller's job, not the wire's — mirrors SeekPayload.
        assertEquals(1000, TeleprompterWpmPayload.decode(TeleprompterWpmPayload.encode(1000)))
    }

    @Test fun `decode returns null for a null payload`() {
        assertNull(TeleprompterWpmPayload.decode(null))
    }

    @Test fun `decode returns null for a wrong-length payload`() {
        assertNull(TeleprompterWpmPayload.decode(byteArrayOf(1, 2, 3)))
        assertNull(TeleprompterWpmPayload.decode(ByteArray(0)))
        assertNull(TeleprompterWpmPayload.decode(ByteArray(8))) // an 8-byte SeekPayload must not decode here
    }

    @Test fun `clamp pins to the rails`() {
        assertEquals(TeleprompterWpmPayload.MIN_WPM, TeleprompterWpmPayload.clamp(10))
        assertEquals(TeleprompterWpmPayload.MAX_WPM, TeleprompterWpmPayload.clamp(9_999))
        assertEquals(150, TeleprompterWpmPayload.clamp(150))
    }

    @Test fun `step nudges by detents and clamps at the rails`() {
        assertEquals(150, TeleprompterWpmPayload.step(140, 1))   // +1 detent × 10
        assertEquals(130, TeleprompterWpmPayload.step(140, -1))  // −1 detent
        assertEquals(160, TeleprompterWpmPayload.step(140, 1, stepWpm = 20))
        assertEquals(TeleprompterWpmPayload.MIN_WPM, TeleprompterWpmPayload.step(TeleprompterWpmPayload.MIN_WPM, -5))
        assertEquals(TeleprompterWpmPayload.MAX_WPM, TeleprompterWpmPayload.step(TeleprompterWpmPayload.MAX_WPM, 5))
    }
}
