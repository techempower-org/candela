package `in`.jphe.storyvox.playback.wear

import `in`.jphe.storyvox.playback.SleepTimerMode
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The watch's sleep-timer screen sends a CMD_SLEEP_SET message carrying a
 * [SleepPayload]; the phone decodes it. Shared wire format so the encode
 * (watch) and decode (phone) sides can't drift — mirrors [SeekPayloadTest] /
 * [TeleprompterWpmPayload].
 *
 * A positive value is a [SleepTimerMode.Duration] in minutes; the sentinel 0 is
 * [SleepTimerMode.EndOfChapter]; anything else (wrong length / negative / null)
 * decodes to null and the phone bridge ignores it.
 */
class SleepPayloadTest {

    @Test fun `round-trips each duration preset`() {
        for (minutes in SleepPayload.DURATION_PRESETS_MIN) {
            val decoded = SleepPayload.decode(SleepPayload.encode(SleepTimerMode.Duration(minutes)))
            assertEquals(SleepTimerMode.Duration(minutes), decoded)
        }
    }

    @Test fun `round-trips end of chapter`() {
        val decoded = SleepPayload.decode(SleepPayload.encode(SleepTimerMode.EndOfChapter))
        assertEquals(SleepTimerMode.EndOfChapter, decoded)
    }

    @Test fun `encodes to exactly 4 bytes (big-endian Int)`() {
        assertEquals(4, SleepPayload.encode(SleepTimerMode.Duration(30)).size)
        assertEquals(4, SleepPayload.encode(SleepTimerMode.EndOfChapter).size)
    }

    @Test fun `decode returns null for null payload`() {
        // MessageClient delivers null for payload-less messages (e.g. the
        // separate CMD_SLEEP_CANCEL). A stray null here must not crash the bridge.
        assertNull(SleepPayload.decode(null))
    }

    @Test fun `decode returns null for wrong-length payload`() {
        assertNull(SleepPayload.decode(byteArrayOf(1, 2, 3)))
        assertNull(SleepPayload.decode(ByteArray(0)))
    }

    @Test fun `decode returns null for a negative (malformed) value`() {
        val negative = ByteBuffer.allocate(4).putInt(-5).array()
        assertNull(SleepPayload.decode(negative))
    }

    @Test fun `duration presets are 15, 30, 45`() {
        assertEquals(listOf(15, 30, 45), SleepPayload.DURATION_PRESETS_MIN)
    }
}
