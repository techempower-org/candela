package `in`.jphe.storyvox.source.audiobook.writer

import java.util.Locale

/**
 * Suggested cache filename + SAF picker default for an exported audiobook.
 * Slugified title keeps multiple exports of the same book distinguishable in
 * the user's Downloads folder. Mirrors the EPUB writer's `buildFileName`.
 *
 * Pure (takes the timestamp as a param) so it's deterministic under test.
 */
object AudiobookFileName {

    /** `.m4b` for the chaptered AAC container, `.mp3` for the fallback path. */
    fun forTitle(title: String, stamp: String, extension: String = "m4b"): String {
        val slug = title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
            .ifBlank { "audiobook" }
        return "$slug-$stamp.$extension"
    }
}
