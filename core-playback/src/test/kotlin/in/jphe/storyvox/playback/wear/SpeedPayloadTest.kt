package `in`.jphe.storyvox.playback.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Shared wire format for the playback-speed remote command, so the watch
 * (encode) and phone (decode) sides can't drift. Mirrors
 * [TeleprompterWpmPayloadTest]; speed is an absolute 4-byte big-endian Float.
 */
class SpeedPayloadTest {

    @Test fun `round-trips a speed`() {
        assertEquals(1.4f, SpeedPayload.decode(SpeedPayload.encode(1.4f))!!, 0.0f)
    }

    @Test fun `encodes to exactly 4 bytes (big-endian Float)`() {
        assertEquals(4, SpeedPayload.encode(1.4f).size)
    }

    @Test fun `the wire transports the value faithfully, without clamping`() {
        // encode/decode are pure transport; range-enforcement is clamp()/the
        // controller's job, not the wire's — mirrors SeekPayload.
        assertEquals(9.0f, SpeedPayload.decode(SpeedPayload.encode(9.0f))!!, 0.0f)
    }

    @Test fun `decode returns null for a null payload`() {
        assertNull(SpeedPayload.decode(null))
    }

    @Test fun `decode returns null for a wrong-length payload`() {
        assertNull(SpeedPayload.decode(byteArrayOf(1, 2, 3)))
        assertNull(SpeedPayload.decode(ByteArray(0)))
        assertNull(SpeedPayload.decode(ByteArray(8))) // an 8-byte SeekPayload must not decode here
    }

    @Test fun `clamp pins to the rails and snaps to the 0_1 grid`() {
        assertEquals(SpeedPayload.MIN_SPEED, SpeedPayload.clamp(0.1f), 0.0f)
        assertEquals(SpeedPayload.MAX_SPEED, SpeedPayload.clamp(9.9f), 0.0f)
        assertEquals(1.5f, SpeedPayload.clamp(1.5f), 0.0f)
        assertEquals(1.4f, SpeedPayload.clamp(1.42f), 0.0f) // snaps off-grid input
    }

    @Test fun `step nudges by detents and clamps at the rails`() {
        assertEquals(1.5f, SpeedPayload.step(1.4f, 1), 0.0f)   // +1 detent × 0.1
        assertEquals(1.3f, SpeedPayload.step(1.4f, -1), 0.0f)  // −1 detent
        assertEquals(1.6f, SpeedPayload.step(1.4f, 1, step = 0.2f), 0.0f)
        assertEquals(SpeedPayload.MIN_SPEED, SpeedPayload.step(SpeedPayload.MIN_SPEED, -5), 0.0f)
        assertEquals(SpeedPayload.MAX_SPEED, SpeedPayload.step(SpeedPayload.MAX_SPEED, 5), 0.0f)
    }
}
