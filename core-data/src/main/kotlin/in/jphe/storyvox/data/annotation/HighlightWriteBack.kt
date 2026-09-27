package `in`.jphe.storyvox.data.annotation

/**
 * Issue #1468 — the seam through which a freshly created highlight is offered
 * to an external knowledge store (today: a self-hosted Memory Palace, see
 * `:source-mempalace`'s `PalaceHighlightWriteBack`).
 *
 * Lives in `:core-data` so `:feature`'s reader can hand a highlight over
 * without taking a dependency on any source module; the implementation is
 * bound by the module that owns the transport.
 *
 * ## Contract
 *  - **Opt-in.** Implementations MUST check their own user setting and drop
 *    the capture silently when write-back is off (the default).
 *  - **Never blocks.** [onHighlightCreated] is a plain (non-suspend) call that
 *    returns immediately; the implementation schedules the actual I/O
 *    (WorkManager, with offline retry) and never throws to the caller.
 *  - **Create only.** Called once per new highlight, not on edits, deletes or
 *    sync-pulled rows, so one highlight maps to one drawer.
 */
interface HighlightWriteBack {
    fun onHighlightCreated(capture: HighlightCapture)
}

/**
 * Everything a write-back target needs to file one highlight. A plain value
 * snapshot taken at creation time (titles included) so the scheduled work
 * does not have to re-read Room, and still files correctly if the reader has
 * moved on to another chapter by the time the network comes back.
 */
data class HighlightCapture(
    /** The [in.jphe.storyvox.data.db.entity.Annotation.id] UUID — the
     *  idempotency key for the scheduled write. */
    val annotationId: String,
    val fictionId: String,
    val chapterId: String,
    val fictionTitle: String,
    val chapterTitle: String,
    val quotedText: String,
    val note: String?,
    /** Inclusive start char offset into the chapter body. */
    val startOffset: Int,
    /** Exclusive end char offset into the chapter body. */
    val endOffset: Int,
    /** Wall-clock millis the highlight was created. */
    val createdAt: Long,
)
