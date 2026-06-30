package `in`.jphe.storyvox.wear.playback

import `in`.jphe.storyvox.playback.PlaybackError
import `in`.jphe.storyvox.playback.PlaybackState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [WearPlaybackBridge.decodeState] — the watch-side decode of the
 * `/playback/state` JSON the phone publishes (#1032).
 *
 * Extracted out of `consume()` as a pure function so the decode contract can be
 * exercised without standing up GMS `DataItem`/`DataClient` (the seam pattern,
 * mirroring [NodeSelection] for #1030 and DocumentImportClassifier for #1000).
 *
 * The phone side ([in.jphe.storyvox.playback.wear.PhoneWearBridge.publishState])
 * encodes with `Json { ignoreUnknownKeys = true }` and NO `encodeDefaults`, so a
 * default-valued field is simply omitted; [phoneJson] mirrors that exactly so the
 * round-trip tests assert the real wire shape, not an idealised one.
 */
class WearStateDecodeTest {

    /** Same config as both bridges — defaults omitted, unknown keys tolerated. */
    private val phoneJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `round-trips a published state through encode then decode`() {
        val published = PlaybackState(
            currentFictionId = "fic-1",
            currentChapterId = "ch-3",
            charOffset = 512,
            durationEstimateMs = 90_000L,
            isPlaying = true,
            bookTitle = "The Title",
            chapterTitle = "Chapter 3",
        )
        val raw = phoneJson.encodeToString(published)

        val decoded = WearPlaybackBridge.decodeState(raw, PlaybackState())

        // data-class equality covers every field, so any future field that the
        // phone encodes but the watch fails to reconstruct trips this.
        assertEquals(published, decoded)
    }

    @Test
    fun `round-trips the voice-paced teleprompter lines`() {
        // #1368 — the phone derives the current/next line from positionChar and
        // ships them in PlaybackState so the wrist renders the hands-free
        // teleprompter without holding the chapter text. Pin that the wire
        // carries them (non-default Strings aren't omitted by the encoder).
        val published = PlaybackState(
            teleprompterEnabled = true,
            teleprompterCurrentLine = "Welcome back to the show.",
            teleprompterNextLine = "Today we are talking about getting connected.",
        )
        val decoded = WearPlaybackBridge.decodeState(
            phoneJson.encodeToString(published),
            PlaybackState(),
        )
        assertEquals("Welcome back to the show.", decoded.teleprompterCurrentLine)
        assertEquals("Today we are talking about getting connected.", decoded.teleprompterNextLine)
    }

    @Test
    fun `round-trips a sealed PlaybackError subtype`() {
        val published = PlaybackState(error = PlaybackError.AzureThrottled("F0 quota exhausted"))
        val raw = phoneJson.encodeToString(published)

        val decoded = WearPlaybackBridge.decodeState(raw, PlaybackState())

        assertEquals(PlaybackError.AzureThrottled("F0 quota exhausted"), decoded.error)
    }

    @Test
    fun `malformed JSON leaves the last-good state intact`() {
        val lastGood = PlaybackState(currentChapterId = "ch-good", charOffset = 99, isPlaying = true)

        val decoded = WearPlaybackBridge.decodeState("{not valid json", lastGood)

        // Identity, not just equality — a bad payload must not even allocate a
        // replacement; the watch keeps showing exactly what it last had.
        assertSame(lastGood, decoded)
    }

    @Test
    fun `unknown sealed-error discriminator from a newer phone keeps last-good`() {
        // Version skew: the phone shipped a PlaybackError subtype this (older)
        // watch build can't resolve. kotlinx.serialization throws on the unknown
        // polymorphic discriminator even with ignoreUnknownKeys — the decode must
        // be caught so the watch doesn't crash and doesn't blank the UI.
        val raw = """{"error":{"type":"in.jphe.storyvox.playback.PlaybackError.FutureV2Error","code":7}}"""
        val lastGood = PlaybackState(isPlaying = true, charOffset = 42)

        val decoded = WearPlaybackBridge.decodeState(raw, lastGood)

        assertSame(lastGood, decoded)
    }

    @Test
    fun `null payload returns current unchanged`() {
        val current = PlaybackState(currentChapterId = "ch-x")
        assertSame(current, WearPlaybackBridge.decodeState(null, current))
    }

    @Test
    fun `decode failure invokes the onError hook`() {
        // #1032 — consume() previously swallowed decode failures in a bare
        // runCatching. The onError seam is what lets WearPlaybackBridge log the
        // dropped payload (and lets this test prove the failure was observed,
        // without Robolectric for android.util.Log).
        var seen: Throwable? = null

        WearPlaybackBridge.decodeState("{bad", PlaybackState()) { seen = it }

        assertNotNull("expected onError to fire on malformed payload", seen)
    }

    @Test
    fun `successful decode does not invoke the onError hook`() {
        val raw = phoneJson.encodeToString(PlaybackState(charOffset = 7))
        var fired = false

        WearPlaybackBridge.decodeState(raw, PlaybackState()) { fired = true }

        assertTrue("onError must not fire on a clean decode", !fired)
    }

    @Test
    fun `extra unknown keys from a newer phone are ignored`() {
        // Forward-compat: a newer phone adds a field the watch's PlaybackState
        // doesn't know. ignoreUnknownKeys lets the known fields through.
        val raw = """{"currentChapterId":"ch-1","charOffset":5,"futureField":true}"""

        val decoded = WearPlaybackBridge.decodeState(raw, PlaybackState())

        assertEquals("ch-1", decoded.currentChapterId)
        assertEquals(5, decoded.charOffset)
    }
}
