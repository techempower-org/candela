package `in`.jphe.storyvox.source.bookshare

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1002 — Bookshare / accessible-library (DAISY) source.
 *
 * Bookshare (a Benetech program) is the largest accessible-book library for
 * people with print disabilities — 1M+ titles in DAISY format, free to
 * qualified users. This is the most mission-aligned source Candela could add.
 *
 * ## Why this is a guarded scaffold (not yet functional)
 *
 * A working integration is gated on three things that need a partnership /
 * legal step, not engineering (full writeup in the #1002 research comment):
 *
 *  1. **Partner `api_key`** — issued only by emailing
 *     `partner-support@bookshare.org`; required on every API v2 endpoint.
 *  2. **Per-user OAuth** — Authorization-Code grant against
 *     `auth.bookshare.org`; the user signs in with their *already
 *     disability-verified* Bookshare account (Bookshare verifies eligibility
 *     at signup, so Candela never collects proof-of-disability itself).
 *  3. **Protected DAISY (PDTB) decryption** — copyrighted downloads are
 *     encrypted per-user, fingerprinted, and watermarked; decryption needs
 *     Bookshare's scheme under partner terms and isn't publicly implementable.
 *
 * Until those land, every call returns [FictionResult.AuthRequired] — the
 * interface's intended "no session" path, which surfaces a sign-in prompt
 * instead of throwing. The non-gated groundwork that DID ship in this PR is
 * the DAISY text parser
 * ([`DaisyParser`][in.jphe.storyvox.source.bookshare.parse.DaisyParser]):
 * once an `api_key` + OAuth land and an *unprotected* DAISY download is
 * available, [chapter] parses it via that parser into [ChapterContent].
 */
@Singleton
@SourcePlugin(
    id = "bookshare",
    displayName = "Bookshare",
    defaultEnabled = false,
    category = SourceCategory.Ebook,
    supportsSearch = true,
    description = "Accessible DAISY library · partner API (gated, see #1002)",
    sourceUrl = "https://www.bookshare.org",
)
class BookshareSource @Inject constructor() : FictionSource {

    override val id: String = SourceIds.BOOKSHARE
    override val displayName: String = "Bookshare"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> = gate()

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> = gate()

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> = gate()

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> = gate()

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> = gate()

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> = gate()

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> = gate()

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> = gate()

    override suspend fun genres(): FictionResult<List<String>> = gate()

    /**
     * Every network path is gated until the Bookshare partnership lands (see
     * the class kdoc + #1002). [FictionResult.AuthRequired] is the interface's
     * intended graceful "no session" return — never throw.
     */
    private fun gate(): FictionResult.AuthRequired = FictionResult.AuthRequired(GATE_MESSAGE)

    companion object {
        private const val GATE_MESSAGE =
            "Bookshare needs a partner API key and your verified Bookshare sign-in, " +
                "plus Protected-DAISY support — not available yet (see #1002)."
    }
}
