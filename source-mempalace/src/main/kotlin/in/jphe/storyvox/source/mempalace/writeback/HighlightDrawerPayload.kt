package `in`.jphe.storyvox.source.mempalace.writeback

import `in`.jphe.storyvox.data.annotation.HighlightCapture
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Issue #1468 — pure builders for the drawer a highlight becomes. No Android,
 * no network: everything the write-back sends is decided here so it is
 * unit-testable (see `HighlightDrawerPayloadTest`).
 *
 * ## Where it lands
 * `wing = candela_highlights`, `room = references`. A dedicated wing keeps a
 * reader's captured passages apart from any other wing on the palace (and
 * browsable as their own wing through the MemPalace source). `references`
 * is one of palace-daemon's canonical rooms — a non-canonical room is a 400
 * at the `/memory` boundary, so this must stay inside that set. The wing is
 * already in palace-daemon's normalised slug form (`[a-z0-9_]`), so what we
 * send is exactly what gets stored.
 *
 * ## Size
 * WorkManager caps a request's input `Data` at 10 KB, and strings are stored
 * as modified UTF-8 (up to 3 bytes per char). [content] therefore clamps the
 * quote and note so the whole drawer stays under [MAX_CONTENT_CHARS]; the
 * clamp is marked with an ellipsis so a truncated passage is never mistaken
 * for the full one.
 */
object HighlightDrawerPayload {

    const val WING: String = "candela_highlights"
    const val ROOM: String = "references"

    /** Hard ceiling on the drawer body (chars). 3000 × 3 bytes ≈ 9 KB, under
     *  WorkManager's 10 KB `Data` limit with room for the key names. */
    const val MAX_CONTENT_CHARS: Int = 3000

    internal const val MAX_QUOTE_CHARS: Int = 2000
    internal const val MAX_NOTE_CHARS: Int = 600
    internal const val MAX_TITLE_CHARS: Int = 150
    private const val ELLIPSIS = "…"

    private val ISO_UTC: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    /**
     * The drawer body — Markdown, so it reads well in the palace viewer and
     * in search results:
     *
     * ```
     * > the quoted passage
     *
     * Note: the reader's note (only when present)
     *
     * — Fiction Title, Chapter Title
     * Position: chars 120–245
     * Highlighted in Candela · 2026-09-27T18:04:05Z
     * fiction: royalroad:123 · chapter: royalroad:123:4 · highlight: <uuid>
     * ```
     *
     * The ids line makes the drawer traceable back to the exact annotation
     * (and lets a future de-dupe pass find it by highlight id).
     */
    fun content(capture: HighlightCapture): String {
        val quote = clamp(capture.quotedText.trim(), MAX_QUOTE_CHARS)
        val note = capture.note?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, MAX_NOTE_CHARS) }
        val fiction = clamp(capture.fictionTitle.trim().ifEmpty { "Untitled" }, MAX_TITLE_CHARS)
        val chapter = clamp(capture.chapterTitle.trim(), MAX_TITLE_CHARS)

        val body = buildString {
            // Block-quote every line so a multi-paragraph selection stays one quote.
            quote.lines().joinTo(this, separator = "\n") { line -> if (line.isEmpty()) ">" else "> $line" }
            append("\n\n")
            if (note != null) {
                append("Note: ").append(note).append("\n\n")
            }
            append("— ").append(fiction)
            if (chapter.isNotEmpty()) append(", ").append(chapter)
            append('\n')
            append("Position: chars ").append(capture.startOffset).append('–').append(capture.endOffset)
            append('\n')
            append("Highlighted in Candela · ").append(ISO_UTC.format(Instant.ofEpochMilli(capture.createdAt)))
            append('\n')
            append("fiction: ").append(capture.fictionId)
            append(" · chapter: ").append(capture.chapterId)
            append(" · highlight: ").append(capture.annotationId)
        }
        // Belt and braces: the per-field clamps already bound this, but ids
        // are unbounded source-supplied strings.
        return clamp(body, MAX_CONTENT_CHARS)
    }

    /** The `POST /memory` JSON body. kotlinx builds it so every quote,
     *  newline and control char in a passage is escaped correctly. */
    fun requestBody(content: String, wing: String = WING, room: String = ROOM): String =
        buildJsonObject {
            put("content", content)
            put("wing", wing)
            put("room", room)
        }.toString()

    internal fun clamp(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - ELLIPSIS.length).trimEnd() + ELLIPSIS
}
