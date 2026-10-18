package `in`.jphe.storyvox.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.ui.component.TestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1808 — the "For you" feed was reachable only from Settings hub → Tools.
 * The quicker entry is a card at the top of the Library grid: shown on the
 * All filter (with or without books), hidden on a shelf filter (a
 * user-scoped view) and while a search is active (it isn't a result).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LibraryForYouEntryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val book = FictionSummary(id = "rss:1", sourceId = "rss", title = "A book", author = "An author")

    private fun render(state: LibraryUiState, books: List<FictionSummary>, onOpenFeed: () -> Unit = {}) {
        composeRule.setContent {
            Box(Modifier.height(1200.dp)) {
                LibraryGridBody(
                    state = state,
                    dedupedFictions = books,
                    onResume = {},
                    onTapFiction = {},
                    onLongPress = {},
                    onOpenTechEmpower = {},
                    onOpenFeed = onOpenFeed,
                )
            }
        }
    }

    @Test
    fun `the All view shows the For you card and a tap opens the feed`() {
        var opened = 0
        render(LibraryUiState(fictions = listOf(book), isLoading = false), listOf(book)) { opened++ }
        composeRule.onNodeWithTag(TestTags.LibraryOpenForYou).assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals("one tap opens the feed once", 1, opened)
    }

    @Test
    fun `an empty library still shows the For you card`() {
        render(LibraryUiState(isLoading = false), emptyList())
        composeRule.onNodeWithTag(TestTags.LibraryOpenForYou).assertIsDisplayed()
    }

    @Test
    fun `a shelf filter hides the For you card`() {
        render(LibraryUiState(fictions = listOf(book), filter = ShelfFilter.OneShelf(Shelf.Reading), isLoading = false), listOf(book))
        composeRule.onNodeWithTag(TestTags.LibraryList).assertExists()
        composeRule.onNodeWithTag(TestTags.LibraryOpenForYou).assertDoesNotExist()
    }

    @Test
    fun `an active search hides the For you card`() {
        render(LibraryUiState(fictions = listOf(book), searchQuery = "book", isLoading = false), listOf(book))
        composeRule.onNodeWithTag(TestTags.LibraryList).assertExists()
        composeRule.onNodeWithTag(TestTags.LibraryOpenForYou).assertDoesNotExist()
    }
}
