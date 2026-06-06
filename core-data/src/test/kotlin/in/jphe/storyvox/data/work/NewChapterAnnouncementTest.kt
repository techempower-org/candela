package `in`.jphe.storyvox.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the Royal Road "65 new chapters" bug.
 *
 * [NewChapterPollWorker] used to report the size of the whole
 * `NOT_DOWNLOADED` backlog as the "new chapters" count. A followed /
 * SUBSCRIBE fiction never downloads its chapters, so that set is the
 * fiction's entire unplayed history — every poll re-announced "65 new
 * chapters" when one chapter had actually landed. [newChapterAnnouncement]
 * reports the delta since the previous poll instead.
 */
class NewChapterAnnouncementTest {

    @Test
    fun `one new chapter on top of a 64-chapter backlog announces 1, not 65`() {
        // 64 chapters already known (never downloaded — all NOT_DOWNLOADED),
        // the source just published chapter 65. The whole backlog of 65 is
        // "missing"; only the last id is genuinely new.
        val prior = (1..64).map { "rr:42:$it" }.toSet()
        val missing = (1..65).map { "rr:42:$it" }

        val a = newChapterAnnouncement(missingChapterIds = missing, priorChapterIds = prior)

        assertEquals(1, a.newCount)
        assertEquals("chapter", a.pluralLabel)
        assertEquals("rr:42:65", a.deepLinkChapterId)
        assertTrue(a.shouldNotify)
    }

    @Test
    fun `multiple new chapters announce the delta and pluralise`() {
        val prior = (1..64).map { "rr:42:$it" }.toSet()
        val missing = (1..67).map { "rr:42:$it" } // 3 new: 65, 66, 67

        val a = newChapterAnnouncement(missingChapterIds = missing, priorChapterIds = prior)

        assertEquals(3, a.newCount)
        assertEquals("chapters", a.pluralLabel)
        // Earliest new chapter is the deep-link target.
        assertEquals("rr:42:65", a.deepLinkChapterId)
        assertTrue(a.shouldNotify)
    }

    @Test
    fun `first poll baseline does not announce the whole backlog as new`() {
        // Brand-new follow: followsList added the fiction row, this is the
        // first poll that hydrated its chapters. Everything looks new but
        // none of it is news to the user.
        val prior = emptySet<String>()
        val missing = (1..65).map { "rr:42:$it" }

        val a = newChapterAnnouncement(missingChapterIds = missing, priorChapterIds = prior)

        assertFalse(a.shouldNotify)
    }

    @Test
    fun `no genuinely new chapters does not notify`() {
        // Poll found the same un-downloaded backlog as before — nothing new.
        val ids = (1..10).map { "rr:42:$it" }

        val a = newChapterAnnouncement(missingChapterIds = ids, priorChapterIds = ids.toSet())

        assertEquals(0, a.newCount)
        assertFalse(a.shouldNotify)
        assertNull(a.deepLinkChapterId)
    }

    @Test
    fun `single new chapter from no prior backlog (second poll) announces 1`() {
        // EAGER/SUBSCRIBE fiction whose backlog was already fully present
        // (downloaded or otherwise no longer NOT_DOWNLOADED), so `missing`
        // holds only the freshly-landed chapter. prior was non-empty, so
        // this is not the first-poll baseline.
        val prior = (1..64).map { "rr:42:$it" }.toSet()
        val missing = listOf("rr:42:65")

        val a = newChapterAnnouncement(missingChapterIds = missing, priorChapterIds = prior)

        assertEquals(1, a.newCount)
        assertEquals("chapter", a.pluralLabel)
        assertEquals("rr:42:65", a.deepLinkChapterId)
        assertTrue(a.shouldNotify)
    }
}
