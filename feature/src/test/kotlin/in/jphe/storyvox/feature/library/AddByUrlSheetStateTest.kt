package `in`.jphe.storyvox.feature.library

import `in`.jphe.storyvox.feature.api.UiRouteCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #700 — pin the shape of [AddByUrlSheetState] so the magic-link
 * multi-match flow (pre-fix the chooser was a stub kdoc TODO) keeps
 * carrying the URL and the candidate list together. The renderer in
 * [AddByUrlSheet] keys off this state and re-submits via
 * [LibraryViewModel.chooseSource]; both sides depend on the data
 * surviving the [AddByUrlSheetState.Submitting] → ChooseSource
 * transition.
 *
 * State-only test on purpose — full
 * [LibraryViewModel.submitAddByUrl] coverage would require building 8
 * fakes (Fiction/Shelf/History/Inbox/Position repos + PlaybackUi +
 * ResumePolicy + FictionRepositoryUi). The state shape is the contract
 * the chooser UX depends on.
 */
class AddByUrlSheetStateTest {

    @Test
    fun `Hidden is a singleton`() {
        assertTrue(AddByUrlSheetState.Hidden === AddByUrlSheetState.Hidden)
    }

    @Test
    fun `Open carries an optional error`() {
        val fresh = AddByUrlSheetState.Open()
        assertNull(fresh.error)

        val failed = AddByUrlSheetState.Open(error = "bad URL")
        assertEquals("bad URL", failed.error)
    }

    @Test
    fun `ChooseSource carries url and candidates`() {
        val candidates = listOf(
            UiRouteCandidate(
                sourceId = "github",
                fictionId = "github:owner/repo",
                confidence = 1.0f,
                label = "GitHub repo",
            ),
            UiRouteCandidate(
                sourceId = "readability",
                fictionId = "readability:abc123",
                confidence = 0.1f,
                label = "Web article (readability)",
            ),
        )
        val state = AddByUrlSheetState.ChooseSource(
            url = "https://github.com/owner/repo",
            candidates = candidates,
        )

        assertEquals("https://github.com/owner/repo", state.url)
        assertEquals(2, state.candidates.size)
        assertEquals("github", state.candidates.first().sourceId)
    }

    @Test
    fun `ChooseSource is not the same as Open or Submitting`() {
        // Pre-#700 the multi-match branch fell through to Submitting
        // (auto-resubmit with top candidate). The chooser branch must
        // be a distinct state so the renderer can switch UI bodies
        // without leaning on an out-of-band flag.
        val choosing: AddByUrlSheetState = AddByUrlSheetState.ChooseSource(
            url = "x",
            candidates = emptyList(),
        )
        assertNotEquals(AddByUrlSheetState.Submitting as Any, choosing as Any)
        assertNotEquals(AddByUrlSheetState.Open() as Any, choosing as Any)
        assertNotEquals(AddByUrlSheetState.Hidden as Any, choosing as Any)
    }
}
