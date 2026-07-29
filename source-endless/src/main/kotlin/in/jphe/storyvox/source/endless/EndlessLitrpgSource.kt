package `in`.jphe.storyvox.source.endless

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
import `in`.jphe.storyvox.source.endless.net.EndlessChapterEntry
import `in`.jphe.storyvox.source.endless.net.EndlessLitrpgApi
import `in`.jphe.storyvox.source.endless.net.EndlessStory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `source-endless` — Candela's read side onto JP's endless-litrpg daemon:
 * a self-hosted LAN service that generates a LitRPG serial forever and
 * renders each chapter with a **cast of voices**, one per character.
 *
 * A `FictionSource` is documented as a "read-side abstraction over a
 * fiction-hosting site". A daemon serving endlessly-generated chapters is
 * one, so the mapping needs no distortion:
 *
 * | `FictionSource` | Daemon |
 * |---|---|
 * | [popular] / [latestUpdates] | the running serial — `GET /api/story` |
 * | [search] | the serial, when the term matches it |
 * | [fictionDetail] | `GET /api/story` + `GET /api/chapters?since=0` |
 * | [chapter] | `GET /api/chapters/{n}` |
 * | [latestRevisionToken] | `latest_chapter` + `prompt_hash` |
 *
 * ## Pre-rendered audio, not on-device TTS
 *
 * Each chapter has an MP3 at `/media/{n}.mp3` rendered with a distinct
 * voice per speaker. That is served as [ChapterContent.audioUrl], so
 * `EnginePlayer` routes it through Media3 and bypasses the TTS pipeline
 * (#373) — the same shape `source-librivox` uses for volunteer
 * recordings. Re-synthesising the text on the phone would replace the
 * cast with a single voice, so it isn't done.
 *
 * The text body is populated **anyway**, for two reasons: the reader shows
 * the prose while the render plays (read-along, as `source-librivox` does
 * since #1224), and a chapter whose audio hasn't been rendered yet has
 * `audioUrl == null` and falls back to Candela's own TTS rather than being
 * unplayable. Both states are normal — the daemon ships text and audio as
 * separate artifacts precisely so a TTS failure cannot cost a chapter
 * (spec §10).
 *
 * ## Sentence highlighting is NOT wired here, and cannot be
 *
 * The daemon publishes exact per-segment `start_ms`/`end_ms` timings, so
 * highlighting the sentence currently being spoken is *possible* — but not
 * from a source module. Candela's highlight chain is entirely internal to
 * the TTS pipeline:
 *
 * ```
 * PcmSource.nextChunk() → PcmChunk.range: SentenceRange
 *   → PlaybackState.currentSentenceRange
 *   → UiPlaybackState.sentenceStart/sentenceEnd
 *   → ReaderView highlight span + auto-scroll
 * ```
 *
 * `EnginePlayer.loadAndPlayAudioStream()` — the branch a non-null
 * `audioUrl` selects — sets `currentSentenceRange = null` explicitly,
 * reasoning that "a live stream has no positional addressing the user can
 * scrub against." That was true of the KVMR radio stream the path was
 * built for. It is **false for this source**, which knows exactly where
 * every sentence starts and ends.
 *
 * There is no seam to fix it from here: no field on `FictionSource`,
 * `ChapterInfo` or `ChapterContent` carries timing — they carry text and
 * URLs. So audio and highlighting are mutually exclusive today, and this
 * module deliberately ships the cast-voiced audio without highlighting
 * rather than throwing away the cast to get it.
 *
 * The follow-up is small and well-shaped, which is why it is a separate
 * change: `:core-playback`'s `PcmIndex`/`PcmIndexEntry` is already this
 * daemon's manifest (per-sentence entries with char offsets plus
 * `byteOffset`/`byteLen`), and `CacheFileSource` already replays that
 * shape while emitting `SentenceRange`. The daemon serves Range-capable
 * 16 kHz mono s16le PCM at a constant 32 bytes/ms, so
 * `byteOffset = start_ms × 32` exactly — verified against live chapters.
 * What's missing is a `PcmSource` that streams a *remote* PCM + index
 * instead of a locally-rendered cache entry. That work belongs in
 * `:core-playback`, under its own review.
 *
 * ## Cleartext status — runtime verification is currently blocked
 *
 * The daemon speaks plain HTTP with no TLS, structurally: the ESP32-C6
 * watch that shares this API cannot do TLS. Android has blocked cleartext
 * by default since 9 (targetSdk >= 28), and Candela's
 * `network_security_config.xml` allowlists exactly one host today.
 *
 * **No cleartext allowlist entry has been added for this daemon**, on
 * purpose. The right entry is a hostname (one `<domain>` line, survives
 * the daemon moving between hosts by re-pointing DNS, and unlike the
 * existing entry it would satisfy the LAN guard in
 * `EndlessLitrpgApi.baseUrlOrNull` without a hardcoded exception) — and
 * that DNS record doesn't exist yet. Allowlisting today's bare RFC1918
 * address instead would put a build-time constant behind a
 * runtime-configurable setting: wrong the first time the daemon moves,
 * which is the same defect as compiling in the URL.
 *
 * Consequence, stated rather than papered over: **this source's unit tests
 * pass and it will not reach the live daemon from a device until that one
 * line lands.** Everything else — parsing, mapping, id shapes, media
 * re-hosting, the LAN guard — is exercised against real captured daemon
 * payloads over loopback, which cleartext rules permit.
 *
 * ## No auth, therefore no auth code
 *
 * Every daemon route is open on a LAN port — a deliberate, documented
 * decision (spec §9.1) following from the watch's inability to do TLS.
 * So [FictionResult.AuthRequired] is never returned, [supportsFollow]
 * stays at its default `false`, and [followsList]/[setFollowed] are inert.
 */
@SourcePlugin(
    id = EndlessLitrpgIds.PLUGIN_ID,
    displayName = "Endless LitRPG",
    // Off until the user points it at their daemon — there is no public
    // instance, so a default-on source would sit in Browse failing for
    // everyone who isn't JP.
    defaultEnabled = false,
    // Chapters play a pre-rendered MP3 through Media3 and bypass TTS,
    // same category as LibriVox / radio.
    category = SourceCategory.AudioStream,
    supportsFollow = false,
    supportsSearch = true,
    description = "Self-hosted endless LitRPG serial · pre-rendered multi-voice MP3 " +
        "(bypasses TTS) · set your daemon host in Settings · no account",
    sourceUrl = "https://github.com/jphein/endlesslitrpg",
)
@Singleton
internal class EndlessLitrpgSource @Inject constructor(
    private val api: EndlessLitrpgApi,
) : FictionSource {

    override val id: String = EndlessLitrpgIds.PLUGIN_ID
    override val displayName: String = "Endless LitRPG"

    // ─── browse ────────────────────────────────────────────────────────

    /**
     * The one serial the daemon hosts, as a single-item page.
     *
     * Page > 1 returns empty rather than repeating page 1 — a paginator
     * that keeps being handed the same item never terminates.
     */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) return emptyPage(page)
        return when (val story = api.story()) {
            is FictionResult.Success -> FictionResult.Success(
                ListPage(
                    items = listOf(story.value.toSummary()),
                    page = 1,
                    hasNext = false,
                ),
            )
            is FictionResult.Failure -> story
        }
    }

    /**
     * Identical to [popular]. With one serial there is no distinction
     * between "popular" and "latest" to draw, and mirroring is what
     * `source-librivox` does rather than 404-ing the tab.
     */
    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        popular(page)

    /**
     * Honest empty. The daemon has no genre facet, and inventing a bucket
     * would put a category in the picker that filters nothing.
     */
    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> = emptyPage(page)

    /**
     * Return the serial when [SearchQuery.term] matches its title,
     * description, protagonist, or any chapter title; empty otherwise.
     *
     * **This is narrower than the spec asked for, deliberately.** Spec
     * §9.6 lists `search` as "chapter title/text search", but
     * [FictionSource.search] returns `ListPage<FictionSummary>` — a list
     * of *fictions*. Returning chapters here would mean minting a fake
     * fiction per chapter, and `FictionSourceIdResolver` would then route
     * each one as its own book: a library full of one-chapter entries with
     * no serial to follow. That spec line assumed a shape this interface
     * does not have.
     *
     * Real chapter-text search belongs in the reader's own
     * `BookTextSearch` over downloaded bodies, where a result is a
     * position in a chapter rather than a library row. Noted for a
     * follow-up; not forced in here.
     *
     * A blank term returns the serial (the browse-like shape), which keeps
     * an empty search box from reading as "no results".
     */
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        val story = when (val result = api.story()) {
            is FictionResult.Success -> result.value
            is FictionResult.Failure -> return result
        }
        if (term.isEmpty() || story.matches(term)) {
            return FictionResult.Success(
                ListPage(items = listOf(story.toSummary()), page = 1, hasNext = false),
            )
        }

        // Story metadata didn't match — fall back to chapter titles, which
        // is the part of §9.6's intent that maps cleanly. A failure to
        // fetch the index is reported, not swallowed into "no results":
        // "the daemon is unreachable" and "your term didn't match" are
        // different answers and the UI renders them differently.
        return when (val chapters = api.chapters()) {
            is FictionResult.Success -> {
                val hit = chapters.value.any { it.title.contains(term, ignoreCase = true) }
                FictionResult.Success(
                    ListPage(
                        items = if (hit) listOf(story.toSummary()) else emptyList(),
                        page = 1,
                        hasNext = false,
                    ),
                )
            }
            is FictionResult.Failure -> chapters
        }
    }

    /** No genre facet on the daemon. */
    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val story = when (val result = api.story()) {
            is FictionResult.Success -> result.value
            is FictionResult.Failure -> return result
        }
        val entries = when (val result = api.chapters()) {
            is FictionResult.Success -> result.value
            is FictionResult.Failure -> return result
        }

        // Sort by chapter number rather than trusting arrival order:
        // `index` becomes the reader's ordering, and a serial read out of
        // order is a spoiler, not a cosmetic glitch.
        val ordered = entries.sortedBy { it.number }
        val chapters = ordered.mapIndexed { index, entry ->
            ChapterInfo(
                id = EndlessLitrpgIds.chapterId(entry.number),
                sourceChapterId = entry.number.toString(),
                index = index,
                title = entry.title.ifBlank { "Chapter ${entry.number}" },
                wordCount = entry.words.takeIf { it > 0 },
                // #1221 — carry the audio URL from TOC time so the chapter
                // row has it at creation and loadAndPlay can't race the
                // download worker. Null when the render hasn't happened
                // yet, which is a normal state for a live serial: the
                // newest chapter's text ships before its audio.
                audioUrl = api.mediaUrl(entry.mp3Url),
            )
        }

        return FictionResult.Success(
            FictionDetail(
                summary = story.toSummary(chapterCount = ordered.size),
                chapters = chapters,
                wordCount = ordered.sumOf { it.words.toLong() }.takeIf { it > 0 },
                lastUpdatedAt = null,
            ),
        )
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val number = EndlessLitrpgIds.chapterNumberOf(chapterId)
            ?: return FictionResult.NotFound(
                "Endless LitRPG: unrecognised chapter id \"$chapterId\"",
            )

        return when (val result = api.chapter(number)) {
            is FictionResult.Success -> {
                val ch = result.value
                // Sourced from mp3_url, never templated from the chapter
                // number: the daemon nulls mp3_url whenever audio is absent
                // or invalidated for a re-render, and a fabricated URL
                // would turn "not rendered yet" into a 404 inside
                // ExoPlayer — a broken chapter instead of one that reads
                // fine. Resolved once and shared by info and content so
                // the two can never disagree.
                val audio = api.mediaUrl(ch.mp3Url)
                FictionResult.Success(
                    ChapterContent(
                        info = ChapterInfo(
                            id = EndlessLitrpgIds.chapterId(ch.number),
                            sourceChapterId = ch.number.toString(),
                            // The TOC owns ordering; a single-chapter fetch
                            // has no view of its neighbours, so index is
                            // derived from the number rather than guessed.
                            index = (ch.number - 1).coerceAtLeast(0),
                            title = ch.title.ifBlank { "Chapter ${ch.number}" },
                            audioUrl = audio,
                        ),
                        htmlBody = EndlessChapterText.htmlBody(ch.textMd),
                        plainBody = EndlessChapterText.plainBody(ch.textMd),
                        audioUrl = audio,
                    ),
                )
            }
            is FictionResult.Failure -> result
        }
    }

    // ─── polling ───────────────────────────────────────────────────────

    /**
     * A cheap revision check, which matters more here than for most
     * sources: an *endless* serial gains chapters indefinitely, so the
     * poll worker would otherwise re-fetch and rebuild the whole detail
     * forever.
     *
     * The token digests the **chapter index** — each entry's number,
     * title, `duration_ms`, `has_audio` and `total_bytes`. That covers
     * every way already-published content can move: a new chapter, a
     * chapter gaining audio, a retitle, and — the one that matters most
     * here — a **re-render**.
     *
     * ## Why not `latest_chapter` + `prompt_hash`
     *
     * That was this method's first implementation and it was wrong. On an
     * endless serial, re-rendering a chapter's audio (a cast change, a
     * voice-resolution fix) is routine rather than exceptional, and it
     * moves **neither** value: the chapter count is unchanged and the
     * premise is unchanged. Measured across a real re-render of all five
     * live chapters, every `duration_ms` changed while
     * `latest_chapter:prompt_hash` stayed byte-identical — so the worker
     * would have skipped the refresh and the reader would have kept the
     * superseded audio indefinitely. [FictionSource.latestRevisionToken]'s
     * contract is explicit that a token must be freshly minted whenever
     * any chapter-affecting content changes; a re-render is exactly that.
     *
     * `duration_ms` and `total_bytes` are the observable outputs of a
     * render, which is what makes them the right things to digest — the
     * index exposes no render id or mtime to key on instead. Two renders
     * that produced a byte-identical length would not move the token, but
     * a render that changed nothing observable is also one there is
     * nothing to refresh for.
     *
     * `prompt_hash` is deliberately **not** included: reading it needs a
     * second request to `/api/story`, which would make this token cost
     * exactly as much as the [fictionDetail] it exists to avoid. It also
     * adds nothing — editing the premise only affects *future* chapters,
     * and those arrive as new index entries the digest already sees.
     *
     * Still cheaper than what it saves: one small request, versus
     * [fictionDetail]'s two plus the detail construction and persistence.
     *
     * The digest is fixed-width rather than a concatenation of every
     * entry, because this token is persisted per fiction and an endless
     * serial's index grows without bound.
     */
    override suspend fun latestRevisionToken(fictionId: String): FictionResult<String?> =
        when (val result = api.chapters()) {
            is FictionResult.Success -> {
                val entries = result.value.sortedBy { it.number }
                val fingerprint = entries.joinToString(separator = "\n") { e ->
                    // has_audio and total_bytes are both included even
                    // though they move together today: they are separate
                    // fields on the wire, and a client that assumed they
                    // agree is a client that breaks when one is backfilled
                    // without the other.
                    "${e.number}|${e.title}|${e.durationMs}|${e.hasAudio}|${e.totalBytes ?: -1L}"
                }
                FictionResult.Success("${entries.size}:${shortDigest(fingerprint)}")
            }
            is FictionResult.Failure -> result
        }

    // ─── auth-gated ────────────────────────────────────────────────────

    /**
     * The daemon has no accounts, so there is no remote follows list.
     * An empty page rather than [FictionResult.AuthRequired]: no sign-in
     * would ever satisfy it, so prompting for one would send the user
     * looking for a credential that cannot exist.
     */
    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        emptyPage(page)

    /** No account-side follow concept; [supportsFollow] stays false. */
    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)

    // ─── helpers ───────────────────────────────────────────────────────

    private fun emptyPage(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(
            ListPage(items = emptyList(), page = page.coerceAtLeast(1), hasNext = false),
        )

    /**
     * First 16 hex chars of the SHA-256 of [input] — a fixed-width
     * change-detection digest for [latestRevisionToken].
     *
     * SHA-256 rather than [String.hashCode] because a collision here is a
     * *missed chapter update*, and 32 bits is thin for a value compared on
     * every poll for the lifetime of an endless serial. 64 bits of digest
     * is ample for change detection (this is not a security boundary — the
     * daemon is unauthenticated by design and nothing trusts this value).
     * `MessageDigest.getInstance("SHA-256")` is guaranteed present on every
     * Android API level this module supports, so it needs no dependency and
     * cannot fail at runtime.
     */
    private fun shortDigest(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun EndlessStory.matches(term: String): Boolean =
        title.contains(term, ignoreCase = true) ||
            description.contains(term, ignoreCase = true) ||
            protagonist.contains(term, ignoreCase = true)

    private fun EndlessStory.toSummary(chapterCount: Int? = null): FictionSummary =
        FictionSummary(
            id = EndlessLitrpgIds.FICTION_ID,
            sourceId = EndlessLitrpgIds.PLUGIN_ID,
            title = title.ifBlank { "Endless LitRPG" },
            // The serial has no human author. Naming what made it is more
            // honest than inventing a byline or leaving the field blank,
            // and it reads correctly on the library card.
            author = "Generated serial",
            description = buildString {
                append(description.trim())
                if (protagonist.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Protagonist: ").append(protagonist)
                }
            }.ifBlank { null },
            tags = listOf("litrpg", "serial", "generated"),
            // An endlessly-generated serial is by definition never
            // finished. ONGOING is also what keeps the poll worker
            // interested in it.
            status = FictionStatus.ONGOING,
            chapterCount = chapterCount ?: this.chapterCount.takeIf { it > 0 },
        )

    /** True when a TOC entry has audio the client can actually fetch.
     *  `has_audio` and a non-null `mp3_url` move together on the live
     *  daemon; requiring both means a half-updated row degrades to a text
     *  chapter rather than a 404 in ExoPlayer. */
    @Suppress("unused")
    private fun EndlessChapterEntry.hasRenderedAudio(): Boolean =
        hasAudio && !mp3Url.isNullOrBlank()
}
