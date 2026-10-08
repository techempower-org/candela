package `in`.jphe.storyvox.feature.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.component.HomeTab
import `in`.jphe.storyvox.ui.component.SideNavRail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1828: in landscape on a phone, the side system bar or cutout inset was
 * subtracted INSIDE the rail's 80 dp, so labels wrapped mid-word
 * ("Playi / ng"). A rail drawn beside a 48 dp start inset must give its
 * labels the same room as a rail with no inset: the label is exactly as
 * tall (one line) in both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SideNavRailInsetsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a side inset does not squeeze the rail labels onto two lines`() {
        composeRule.setContent {
            Row {
                Box(Modifier.height(900.dp)) {
                    SideNavRail(selected = HomeTab.Playing, onSelect = {}, insets = WindowInsets(0.dp))
                }
                Box(Modifier.height(900.dp)) {
                    SideNavRail(selected = HomeTab.Playing, onSelect = {}, insets = WindowInsets(left = 48.dp))
                }
            }
        }
        val labels = composeRule
            .onAllNodesWithText(HomeTab.Playing.label, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("one Playing label per rail", 2, labels.size)
        val (plain, inset) = labels.map { it.boundsInRoot.height }
        assertEquals("label height with a 48 dp side inset vs none", plain, inset, 0.5f)
    }

    @Test
    fun `on a short landscape screen the last tab can be scrolled into view`() {
        // 6 tabs x 72 dp = 432 dp; a landscape phone has about 300-400 dp.
        // Before #1828 part 2, the rail didn't scroll and Settings was cut off.
        composeRule.setContent {
            Box(Modifier.height(300.dp)) {
                SideNavRail(selected = HomeTab.Playing, onSelect = {}, insets = WindowInsets(0.dp))
            }
        }
        val last = HomeTab.entries.last().label
        composeRule.onNodeWithText(last, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
