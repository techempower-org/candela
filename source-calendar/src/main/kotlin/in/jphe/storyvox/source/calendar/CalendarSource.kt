package `in`.jphe.storyvox.source.calendar

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1495 — the device calendar as a narratable fiction.
 *
 * Reads whatever the phone already syncs (Google / Samsung / Outlook / local)
 * through the on-device [CalendarReader] — **zero network, zero cloud API, no
 * keys**; the data is narrated locally and never leaves the device (privacy.md
 * §2.9). Same local-provider posture as `:source-ocr` / `:source-epub`, so it
 * skips the HTTP contract kit.
 *
 * ## Reading model
 * Exactly one fiction, "My Calendar" ([FICTION_ID]), with three fixed chapters —
 * **Today / Tomorrow / This Week** — each narrating its events (time, title,
 * location, duration; all-day events summarised first). The chapter bodies are
 * computed live from the current day, so a re-open always reads the current
 * agenda; [latestRevisionToken] mints a per-day token so the poll worker
 * refreshes when the date rolls over.
 *
 * ## Permission gate (never nagging)
 * `READ_CALENDAR` is requested only on an explicit Browse tap, never at launch.
 * Until it's granted the list calls return an **empty** page (not a hard
 * failure) — [`RealBrowsePaginator`][in.jphe.storyvox.feature.browse] maps any
 * failure to an error banner, but an empty page lets Browse render the
 * "Grant calendar access" rationale CTA instead (the `AnonymousNotionDelegate`
 * precedent). Detail/chapter reads for a stale entry answer `AuthRequired`.
 */
@SourcePlugin(
    id = "calendar",
    displayName = "Calendar",
    defaultEnabled = false,
    category = SourceCategory.Other,
    supportsFollow = false,
    supportsSearch = false,
    description = "Your device calendar, read aloud · on-device · zero-network",
    sourceUrl = "",
    chipLabel = "Calendar",
    iconName = "CalendarMonth",
)
@Singleton
internal class CalendarSource(
    private val reader: CalendarReader,
    private val nowMillis: () -> Long,
    private val zone: () -> ZoneId,
) : FictionSource {

    @Inject
    constructor(reader: CalendarReader) : this(
        reader = reader,
        nowMillis = System::currentTimeMillis,
        zone = ZoneId::systemDefault,
    )

    override val id: String = "calendar"
    override val displayName: String = "Calendar"

    // ─── browse ────────────────────────────────────────────────────────────
    //
    // One fiction when access is granted, nothing when it isn't. Returning an
    // empty page (rather than AuthRequired) is deliberate: it routes Browse to
    // the "Grant calendar access" empty-state CTA instead of an error banner.

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        calendarListing()

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        calendarListing()

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        // No meaningful search over a single agenda fiction (supportsSearch=false
        // hides the tab); mirror popular() so a stray call still behaves.
        calendarListing()

    private fun calendarListing(): FictionResult<ListPage<FictionSummary>> {
        val items = if (reader.hasPermission()) listOf(summary()) else emptyList()
        return FictionResult.Success(ListPage(items = items, page = 1, hasNext = false))
    }

    // ─── detail ────────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        if (fictionId != FICTION_ID) return FictionResult.NotFound("Unknown calendar fiction: $fictionId")
        if (!reader.hasPermission()) {
            return FictionResult.AuthRequired("Calendar access is off — grant it in Browse to read your agenda.")
        }
        val chapters = buildChapters().mapIndexed { index, ch ->
            ChapterInfo(
                id = chapterId(ch.bucket.key),
                sourceChapterId = ch.bucket.key,
                index = index,
                title = ch.title,
                wordCount = wordCount(ch.plain),
            )
        }
        return FictionResult.Success(FictionDetail(summary = summary(), chapters = chapters))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        if (fictionId != FICTION_ID) return FictionResult.NotFound("Unknown calendar fiction: $fictionId")
        if (!reader.hasPermission()) {
            return FictionResult.AuthRequired("Calendar access is off — grant it in Browse to read your agenda.")
        }
        val chapters = buildChapters()
        val (index, ch) = chapters.withIndex()
            .firstOrNull { chapterId(it.value.bucket.key) == chapterId }
            ?: return FictionResult.NotFound("Unknown calendar chapter: $chapterId")

        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = ch.bucket.key,
                    index = index,
                    title = ch.title,
                    wordCount = wordCount(ch.plain),
                ),
                htmlBody = ch.html,
                plainBody = ch.plain,
            ),
        )
    }

    // ─── auth-gated (calendar has no follow concept) ─────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    // ─── polling: refresh once per day ───────────────────────────────────────

    override suspend fun latestRevisionToken(fictionId: String): FictionResult<String?> {
        if (fictionId != FICTION_ID) return FictionResult.Success(null)
        // Date-in-zone token: identical all day, changes at local midnight — the
        // poll worker re-fetches the agenda exactly once per day.
        val date = Instant.ofEpochMilli(nowMillis()).atZone(zone()).toLocalDate()
        return FictionResult.Success(date.toString())
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private suspend fun buildChapters(): List<CalendarAgenda.Chapter> {
        val now = nowMillis()
        val z = zone()
        val window = CalendarAgenda.queryWindowUtcMillis(now, z)
        val events = reader.events(window.first, window.last + 1)
        return CalendarAgenda.chapters(events, now, z)
    }

    private fun summary(): FictionSummary = FictionSummary(
        id = FICTION_ID,
        sourceId = id,
        title = "My Calendar",
        author = "",
        description = "Your agenda, read aloud — today, tomorrow, and the week ahead. " +
            "On your device only; nothing leaves the phone.",
        status = FictionStatus.ONGOING, // an ever-updating agenda, never "complete"
        chapterCount = 3,
    )

    private fun chapterId(bucketKey: String): String = "$FICTION_ID::$bucketKey"

    private fun wordCount(text: String): Int =
        text.split(Regex("\\s+")).count { it.isNotBlank() }

    companion object {
        /** The single, stable fiction id this source exposes. */
        const val FICTION_ID = "device-calendar"
    }
}
