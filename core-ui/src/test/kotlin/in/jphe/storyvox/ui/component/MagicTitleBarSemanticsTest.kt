package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1136 — regression guard for `MagicTitleBar` heading semantics.
 *
 * `MagicTitleBar` is the shared title bar for every primary-nav surface
 * (Library / Browse / Follows / Voice Library / Settings Hub). Its title
 * `Text` must carry `Modifier.semantics { heading() }` so TalkBack's
 * heading-navigation gesture can jump to the screen title — before #1136
 * `heading()` appeared 0× in the whole app and screen-reader users had no
 * heading structure to navigate by.
 *
 * As with [BottomTabBarSemanticsTest], Compose semantics can't be asserted
 * from the unit-test source set (no Robolectric / ComposeTestRule), so this
 * pins the structural marker constant. If a refactor drops the heading
 * semantics without proving an equivalent announcement on a real device
 * with TalkBack, this test fails and forces re-verification.
 */
class MagicTitleBarSemanticsTest {

    @Test
    fun `MagicTitleBar marks its title as a heading per issue #1136`() {
        assertTrue(
            "MagicTitleBar's title Text must carry Modifier.semantics { heading() } " +
                "so TalkBack heading-navigation reaches every primary-nav screen title",
            magicTitleBarMarksTitleAsHeading,
        )
    }
}
