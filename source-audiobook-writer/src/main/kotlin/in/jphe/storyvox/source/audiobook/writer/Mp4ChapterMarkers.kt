package `in`.jphe.storyvox.source.audiobook.writer

/**
 * One chapter marker in the finished M4B: a label + its start offset from the
 * beginning of the book.
 */
data class ChapterMarker(
    val title: String,
    val startMs: Long,
)

/**
 * Pure timecode math + payload building for M4B chapter markers (issue #1003).
 *
 * Kept Android-free so the offset arithmetic and the QuickTime text-sample
 * encoding are unit-testable without a `MediaMuxer`. The Android encoder
 * (`AacM4bEncoder` in :core-playback) feeds in each chapter's rendered
 * duration and uses the returned [ChapterMarker]s + text samples to write a
 * QuickTime `text`/chapter track that audiobook players read.
 */
object Mp4ChapterMarkers {

    /**
     * Turn per-chapter durations (in the order the chapters were rendered)
     * into absolute start offsets. The first chapter starts at 0; each
     * subsequent chapter starts at the running sum of all prior durations.
     *
     * [titles] and [durationsMs] must be the same length; mismatched input is
     * a programming error at the call site (the encoder pairs each rendered
     * chapter with its title) and throws.
     */
    fun markers(titles: List<String>, durationsMs: List<Long>): List<ChapterMarker> {
        require(titles.size == durationsMs.size) {
            "titles (${titles.size}) and durationsMs (${durationsMs.size}) must align"
        }
        var acc = 0L
        return titles.mapIndexed { i, title ->
            val marker = ChapterMarker(title = title, startMs = acc)
            acc += durationsMs[i].coerceAtLeast(0L)
            marker
        }
    }

    /**
     * Encode one chapter title as a QuickTime `text` sample payload.
     *
     * The QuickTime text-sample wire format is: a big-endian `uint16` length
     * prefix, then the UTF-8 text bytes. (Optional style/extension atoms may
     * follow; players that show chapter names only need the length-prefixed
     * string, which is what we emit.) This is the sample data the muxer writes
     * into the chapter track for each marker.
     */
    fun textSample(title: String): ByteArray {
        val utf8 = title.toByteArray(Charsets.UTF_8)
        // Cap at uint16 — chapter titles are short; clamp defensively so the
        // length prefix can't overflow on a pathological title.
        val len = utf8.size.coerceAtMost(0xFFFF)
        val out = ByteArray(2 + len)
        out[0] = ((len ushr 8) and 0xFF).toByte()
        out[1] = (len and 0xFF).toByte()
        System.arraycopy(utf8, 0, out, 2, len)
        return out
    }
}
