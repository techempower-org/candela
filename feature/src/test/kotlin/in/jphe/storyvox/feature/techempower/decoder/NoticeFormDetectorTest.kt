package `in`.jphe.storyvox.feature.techempower.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1516 — pins form-number detection + lookup. Pure JVM, no OCR/Android.
 */
class NoticeFormDetectorTest {

    private fun explainer(form: String, vararg aliases: String) = NoticeExplainer(
        formNumber = form,
        aliases = aliases.toList(),
        title = Localized("t"),
        whatItMeans = Localized("m"),
        whyYouGotIt = Localized("w"),
        whatToDo = Localized("d"),
        verifiedAt = "2026-07-04",
    )

    private val corpus = ExplainerCorpus(
        metadata = DecoderMetadata(),
        explainers = listOf(
            explainer("NA 200", "NA200", "NA-200"),
            explainer("SAR 7", "SAR7"),
        ),
    )

    @Test
    fun detectsFormNumberInProse() {
        val text = "Condado de ... this is your Notice of Action NA 200 dated 07/01."
        assertEquals(listOf("NA200"), NoticeFormDetector.detectCandidates(text))
    }

    @Test
    fun normalizesSpacingAndDashes() {
        assertEquals("NA200", normalizeFormNumber("NA 200"))
        assertEquals("NA200", normalizeFormNumber("na-200"))
        assertEquals("NA200", normalizeFormNumber("NA200"))
        assertEquals("SAR7", normalizeFormNumber("SAR 7"))
    }

    @Test
    fun collectsMultipleCandidatesInOrderDeduped() {
        val text = "See NA 200 and also MC 355. Reminder: NA 200 again."
        assertEquals(listOf("NA200", "MC355"), NoticeFormDetector.detectCandidates(text))
    }

    @Test
    fun firstKnownResolvesKnownForm() {
        val hit = NoticeFormDetector.firstKnown("Your NA 200 notice", corpus)
        assertNotNull(hit)
        assertEquals("NA 200", hit!!.formNumber)
    }

    @Test
    fun firstKnownMatchesViaAlias() {
        val hit = NoticeFormDetector.firstKnown("footer: NA200", corpus)
        assertNotNull(hit)
        assertEquals("NA 200", hit!!.formNumber)
    }

    @Test
    fun firstKnownSkipsUnknownToFindKnown() {
        // XX 999 is unknown; NA 200 is known and comes later — still resolves.
        val hit = NoticeFormDetector.firstKnown("XX 999 ... NA 200", corpus)
        assertNotNull(hit)
        assertEquals("NA 200", hit!!.formNumber)
    }

    @Test
    fun unknownFormReturnsNull() {
        assertNull(NoticeFormDetector.firstKnown("Form XX 999 only", corpus))
    }

    @Test
    fun emptyTextHasNoCandidates() {
        assertTrue(NoticeFormDetector.detectCandidates("no form numbers here").isEmpty())
    }
}
