package `in`.jphe.storyvox.feature.library

import kotlinx.coroutines.flow.Flow

/**
 * Issue #793 — Library "All" shelf sort modes. Order matters: the enum's
 * declaration order is the order rendered in the sort dropdown, and the
 * `name` is the persisted-preference value (so renames are migrations).
 *
 *  - [Title] / [Author] are alphabetical, ascending. Title is the
 *    historical default (pre-#793 the library was hard-coded to
 *    alphabetical-by-title via `FictionDao.observeLibrary`'s
 *    `addedToLibraryAt DESC` then in-memory sort — kept as the
 *    landing value so existing users don't see their grid scramble
 *    on first launch of v0.5.95+).
 *  - [RecentlyAdded] is by [FictionSummary.addedAt] DESC; null
 *    timestamps (legacy rows that pre-date the column) sort last.
 *  - [RecentlyPlayed] is by `lastPlayedAt` DESC; null (never
 *    started) sorts last.
 *  - [LongestUnread] is "what did I forget about?" — rows the user
 *    has never opened (`lastPlayedAt == null`), sorted by addedAt
 *    ASC (oldest at top). Rows with any play time fall to the
 *    bottom in addedAt DESC.
 */
enum class LibrarySortMode {
    Title,
    Author,
    RecentlyAdded,
    RecentlyPlayed,
    LongestUnread;

    companion object {
        val DEFAULT: LibrarySortMode = Title

        /** Lenient lookup used by the persistence layer. */
        fun fromNameOrDefault(raw: String?): LibrarySortMode =
            raw?.let { v -> entries.firstOrNull { it.name == v } } ?: DEFAULT
    }
}

/**
 * Issue #793 — persistent storage for the user's chosen Library sort
 * mode. Implementation lives in the `:app` module (DataStore-backed)
 * because the shared `storyvox_settings` DataStore extension lives
 * there; the feature module compiles against the interface so the
 * ViewModel stays implementation-agnostic.
 */
interface LibrarySortStore {
    /** Current selection; emits the persisted value, defaulting to
     *  [LibrarySortMode.DEFAULT] on first launch / missing key. */
    fun observe(): Flow<LibrarySortMode>

    /** Write a new selection. Idempotent; same-value writes are a no-op
     *  at the DataStore level. */
    suspend fun set(mode: LibrarySortMode)
}
