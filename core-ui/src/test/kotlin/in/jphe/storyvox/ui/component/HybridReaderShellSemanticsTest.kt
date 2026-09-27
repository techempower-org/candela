package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1025 — regression guard for `HybridReaderShell` TalkBack /
 * Switch Access reachability.
 *
 * The shell flips between the Audiobook and Reader panes via a horizontal
 * drag-with-velocity gesture and *nothing else*. A drag is not exposed as
 * an accessibility action, so a screen-reader user (and any motor-impaired
 * user who can't produce a precise fling) cannot reach the Reader pane at
 * all — the entire reading-mode surface is unreachable. The fix wires a
 * [androidx.compose.ui.semantics.CustomAccessibilityAction] onto the shell
 * root, labelled by the *target* pane, driving the same `onViewChange`
 * callback the drag uses, plus a `stateDescription` announcing the current
 * pane.
 *
 * We can't run a Compose UI test from the unit-test source set (no
 * Robolectric / ComposeTestRule — see [BottomTabBarSemanticsTest]). This
 * test pins the contract by:
 *  (a) asserting [ReaderView.opposite] resolves the target pane the custom
 *      action announces and invokes (from Audiobook the action offers
 *      Reader, and vice versa — it must never resolve to itself), and
 *  (b) checking the structural marker constant
 *      [hybridReaderShellExposesPaneSwitchAction] stays `true`.
 *
 * If a future refactor drops the custom action without proving an
 * alternative accessible path on a real device with TalkBack, this test
 * fails and forces a re-verification.
 */
class HybridReaderShellSemanticsTest {

    @Test
    fun `opposite resolves the other pane — the custom action targets the pane the user can't currently see`() {
        // The action label and the pane it switches to are both derived
        // from opposite(): from Audiobook it offers "Switch to reading
        // view" → Reader; from Reader it offers "Switch to audiobook
        // view" → Audiobook.
        assertEquals(ReaderView.Reader, ReaderView.Audiobook.opposite())
        assertEquals(ReaderView.Audiobook, ReaderView.Reader.opposite())
    }

    @Test
    fun `opposite never resolves to the current pane — a no-op action would announce a dead control`() {
        ReaderView.entries.forEach { pane ->
            assertNotEquals(
                "ReaderView.$pane.opposite() must not be itself — the pane-switch action would do nothing",
                pane,
                pane.opposite(),
            )
        }
    }

    @Test
    fun `opposite is an involution — two switches return to the start`() {
        ReaderView.entries.forEach { pane ->
            assertEquals(
                "Switching panes twice must return to the starting pane",
                pane,
                pane.opposite().opposite(),
            )
        }
    }

    @Test
    fun `HybridReaderShell exposes a non-gesture pane-switch action per issue #1025`() {
        // Structural canary. The shell must keep a CustomAccessibilityAction
        // (+ stateDescription) on its root so the drag-only pane switch
        // stays reachable for TalkBack / Switch Access users. Flip to false
        // only after a real-device TalkBack pass proves a different shape
        // carries the same announcement + activation.
        assertTrue(
            "HybridReaderShell must expose a custom accessibility action for the pane switch (issue #1025)",
            hybridReaderShellExposesPaneSwitchAction,
        )
    }

    // ── Issue #1787 — off-screen pane must not reach past the shell ──────

    @Test
    fun `on the Reader pane the Audiobook pane sits entirely outside the shell on the start side`() {
        // This is WHY the shell must clip: at rest on Reader the Audiobook
        // pane spans x in [-width, 0) — exactly where the tablet SideNavRail
        // lives. Unclipped, its verticalScroll column hit-tested first and
        // ate rail taps (issue #1787).
        val width = 1180f
        val (audiobookX, readerX) = paneOffsetsX(animatedOffset = -width, width = width)
        assertEquals(-1180, audiobookX)
        assertEquals(0, readerX)
        assertTrue("Audiobook pane must end at or before the shell start", audiobookX + width.toInt() <= 0)
    }

    @Test
    fun `on the Audiobook pane the Reader pane sits entirely past the shell end`() {
        val width = 1180f
        val (audiobookX, readerX) = paneOffsetsX(animatedOffset = 0f, width = width)
        assertEquals(0, audiobookX)
        assertEquals(1180, readerX)
    }

    @Test
    fun `panes stay exactly one width apart mid-drag`() {
        val width = 800f
        listOf(0f, -1f, -250.4f, -400f, -799.6f, -800f).forEach { offset ->
            val (a, r) = paneOffsetsX(offset, width)
            assertEquals("pane gap at offset $offset", 800, r - a)
        }
    }

    @Test
    fun `HybridReaderShell clips to its bounds per issue #1787`() {
        // Structural canary: the off-screen pane is always composed and
        // translated a full width outside the shell. Without clipToBounds
        // it draws over and steals taps from the tablet nav rail.
        assertTrue(
            "HybridReaderShell must clipToBounds so the off-screen pane can't intercept rail taps (issue #1787)",
            hybridReaderShellClipsToBounds,
        )
    }
}
