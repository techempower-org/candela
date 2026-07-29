package `in`.jphe.storyvox.source.endless

/**
 * Id scheme for `source-endless`.
 *
 * ## Why the shapes are not negotiable
 *
 * `FictionSourceIdResolver` (#981) routes a stored fiction back to its
 * owning source by the colon prefix of its id, and **defaults a
 * colon-less id to Royal Road**. A bare id therefore compiles, passes
 * review, and then silently misroutes every fiction this source produces
 * — detail and reader never open. So every id is
 * `"<pluginId>:<localId>"`, and chapter ids additionally carry `::` so a
 * chapter id is globally unique across fictions and joins on it stay 1:1
 * (#1261).
 *
 * ## Why the fiction id is a constant
 *
 * The daemon serves exactly one serial: `GET /api/story` returns a bare
 * object with no id field, because there is nothing to disambiguate. So
 * the local id is the fixed string [SERIAL] rather than something derived
 * from the story's title — a title-derived id would change under JP the
 * moment he edits the premise, orphaning the library row, the playback
 * position and the downloaded chapters. A constant id survives a retitle,
 * which is the behaviour a reader wants from an ongoing serial.
 *
 * If the daemon ever serves multiple serials, this is where that change
 * lands: the constant becomes a parameter and [chapterId] already
 * namespaces chapters under their fiction.
 */
internal object EndlessLitrpgIds {

    /** The `@SourcePlugin` id — the single source of truth for identity. */
    internal const val PLUGIN_ID = "endless"

    /** Local id of the one serial the daemon hosts. */
    internal const val SERIAL = "serial"

    /** `"endless:serial"`. */
    internal const val FICTION_ID = "$PLUGIN_ID:$SERIAL"

    /**
     * `"endless:serial::5"` for chapter 5. The chapter *number* is the
     * daemon's own stable key (`/api/chapters/{n}`), so it round-trips
     * without a lookup table.
     */
    internal fun chapterId(number: Int): String = "$FICTION_ID::$number"

    /**
     * Recover the chapter number from a [chapterId], or null if it isn't
     * one of ours.
     *
     * Tolerant of a bare number and of a zero-padded one: chapter ids are
     * persisted in Room and arrive back from the download worker, deep
     * links, and cross-device sync rows written by other app versions, so
     * a stricter parse would strand chapters that are perfectly
     * addressable. Returns null rather than throwing — an unparseable id
     * is a `NotFound`, not a crash.
     */
    internal fun chapterNumberOf(chapterId: String): Int? =
        chapterId
            .substringAfterLast("::", missingDelimiterValue = chapterId)
            .trim()
            // `toIntOrNull` already parses a zero-padded number ("0005" → 5)
            // and already returns null for an empty or non-numeric string, so
            // no pre-stripping is wanted here. An earlier version stripped
            // leading zeros and substituted "0" for the empty result, which
            // turned a *genuinely empty* tail ("endless:serial::") into
            // chapter 0 — a real id pointing at a chapter that doesn't exist.
            // The unit test caught it; the fix is to stop pre-processing.
            .toIntOrNull()
}
