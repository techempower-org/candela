package `in`.jphe.storyvox.data.intent

/**
 * Issue #1478 — the single source of truth for the `storyvox.open_reader.*`
 * intent-extra keys that deep-link the reader.
 *
 * Four sites build or read these extras and used to hand-mirror the literals,
 * each guarded only by a "kept in sync by string" comment:
 *  - `NewChapterNotifier` (`:core-data`) — new-chapter notification tap; the
 *    only emitter that sets [EXTRA_PRELOAD] (#1455).
 *  - `StoryvoxPlaybackService` (`:core-playback`) — playback-notification /
 *    lock-screen tap.
 *  - `NowPlayingWidgetRenderer` (`:app`) — now-playing home-screen widget tap.
 *  - `DeepLinkResolver` (`:app`) — the read side that decodes the extras into a
 *    reader route.
 *
 * These strings are baked into `PendingIntent`s already sitting in users'
 * notification shades and home-screen widgets, so the VALUES are a
 * backward-compatibility contract: **never change a literal below.** A drifted
 * key is a silently-broken deep link — exactly the class of bug #1455 fixed.
 * Consolidating the declarations here lets the compiler enforce the sync the
 * comments used to ask humans for; `ReaderIntentContractTest` additionally
 * locks each value byte-for-byte against a future rename.
 *
 * Lives in `:core-data` — the lowest module all four sites already depend on —
 * and holds only `String` constants (no `android.content.Intent` coupling), so
 * it unit-tests without Robolectric, mirroring [`in`.jphe.storyvox.data.share.FictionShareLink].
 */
object ReaderIntentContract {
    /** String fiction id the tap should open. Value frozen — see class doc. */
    const val EXTRA_FICTION_ID: String = "storyvox.open_reader.fiction_id"

    /** String chapter id the tap should open. Value frozen — see class doc. */
    const val EXTRA_CHAPTER_ID: String = "storyvox.open_reader.chapter_id"

    /**
     * Boolean flag (#1455) set only by the new-chapter notification: the target
     * chapter has never been loaded, so `MainActivity` must `startListening` it
     * before navigating (the reader is a passive view of the PlaybackController).
     * The playback notification and now-playing widget point at the
     * already-playing chapter and omit this flag. Value frozen — see class doc.
     */
    const val EXTRA_PRELOAD: String = "storyvox.open_reader.preload"
}
