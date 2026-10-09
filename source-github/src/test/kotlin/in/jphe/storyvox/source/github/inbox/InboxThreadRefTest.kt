package `in`.jphe.storyvox.source.github.inbox

import `in`.jphe.storyvox.source.github.inbox.InboxNarration.ThreadRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1841 slice 1: a release thread id (`owner/repo/releases/<id>`) parses and
 * round-trips, and the existing issue/PR ids are byte-for-byte unchanged,
 * because they're part of stored library fiction ids.
 */
class InboxThreadRefTest {

    @Test fun `existing issue and pull ids are unchanged`() {
        for (id in listOf("o/r/issues/12", "o/r/pull/7")) {
            assertEquals(id, InboxNarration.parseLocalId(id)!!.localId)
        }
        assertTrue(InboxNarration.parseLocalId("o/r/pull/7")!!.isPull)
        assertFalse(InboxNarration.parseLocalId("o/r/issues/12")!!.isPull)
        assertEquals(ThreadRef("o", "r", true, 7), InboxNarration.parseLocalId("o/r/pull/7"))
    }

    @Test fun `a release local id parses and round-trips`() {
        val ref = InboxNarration.parseLocalId("o/r/releases/250123456")!!
        assertEquals(ThreadRef.Kind.Release, ref.kind)
        assertFalse(ref.isPull)
        assertEquals(250123456, ref.number)
        assertEquals("o/r/releases/250123456", ref.localId)
    }

    @Test fun `a release notification subject url parses`() {
        val ref = InboxNarration.parseApiUrl("https://api.github.com/repos/o/r/releases/42")!!
        assertEquals(ThreadRef.Kind.Release, ref.kind)
        assertEquals("o/r/releases/42", ref.localId)
    }

    @Test fun `unknown kinds and oversized ids are still rejected`() {
        assertNull(InboxNarration.parseLocalId("o/r/discussions/3"))
        assertNull(InboxNarration.parseLocalId("o/r/releases/99999999999"))
    }
}
