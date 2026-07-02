package `in`.jphe.storyvox.data.intent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1478 — locks the `storyvox.open_reader.*` extra keys byte-for-byte.
 *
 * These strings live inside `PendingIntent`s already delivered to users'
 * notification shades and home-screen widgets. Consolidating the four
 * hand-mirrored copies into [ReaderIntentContract] made the compiler enforce
 * that the emit and read sides agree — but the compiler is happy with ANY
 * shared value, so a well-meaning "cleanup" rename would still break every
 * live deep link. This test freezes the literals against that: if you must
 * change a value here, you are breaking backward compatibility on purpose.
 */
class ReaderIntentContractTest {

    @Test
    fun fictionIdKey_isFrozen() {
        assertEquals("storyvox.open_reader.fiction_id", ReaderIntentContract.EXTRA_FICTION_ID)
    }

    @Test
    fun chapterIdKey_isFrozen() {
        assertEquals("storyvox.open_reader.chapter_id", ReaderIntentContract.EXTRA_CHAPTER_ID)
    }

    @Test
    fun preloadKey_isFrozen() {
        assertEquals("storyvox.open_reader.preload", ReaderIntentContract.EXTRA_PRELOAD)
    }

    @Test
    fun keysAreDistinct() {
        val keys = setOf(
            ReaderIntentContract.EXTRA_FICTION_ID,
            ReaderIntentContract.EXTRA_CHAPTER_ID,
            ReaderIntentContract.EXTRA_PRELOAD,
        )
        assertEquals(3, keys.size)
    }
}
