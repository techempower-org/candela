package `in`.jphe.storyvox.playback.briefing

import `in`.jphe.storyvox.data.briefing.BriefingItem
import kotlinx.serialization.Serializable

/**
 * #1467 — where an in-flight briefing's queue and cursor are kept so they
 * survive process death. [BriefingQueueController] writes on every start,
 * advance, finish and stop, and restores on its first construction in a new
 * process. Production is [BriefingSettingsStore]; tests use an in-memory fake.
 */
interface BriefingSessionStore {
    /** Persist [saved], or clear the stored session when null. */
    suspend fun saveSession(saved: SavedBriefingSession?)

    /** The stored session, or null when none (or it can't be decoded). */
    suspend fun loadSession(): SavedBriefingSession?

    /** Keeps nothing: a briefing lives only as long as the process. */
    object None : BriefingSessionStore {
        override suspend fun saveSession(saved: SavedBriefingSession?) = Unit
        override suspend fun loadSession(): SavedBriefingSession? = null
    }
}

/** The durable form of a [BriefingSession], plus what's needed to resume it. */
@Serializable
data class SavedBriefingSession(
    val items: List<BriefingItem>,
    val index: Int,
    val advanceOnChapterDone: Boolean,
    val savedAtMillis: Long,
)
