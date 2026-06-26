package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1148 — regression guard for `BrassProgressTrack` accessibility
 * seek semantics.
 *
 * Before #1148 the chapter scrubber exposed only a `contentDescription`
 * (a value with no settable action), so TalkBack / Switch Access users
 * could hear "Playback progress, 2:04 of 47:12" but had **no way to
 * seek** — failing WCAG 4.1.2 (Name/Role/Value), 2.1.1 (Keyboard) and
 * 2.5.7 (Dragging Movements). The fix re-expresses the seek affordance
 * declaratively: `progressBarRangeInfo` + `setProgress` make it an
 * adjustable slider, and ±30s `CustomAccessibilityAction`s mirror the
 * Replay30 / Forward30 skip buttons.
 *
 * The unit-test source set has no Robolectric / ComposeTestRule (per the
 * `BottomTabBarSemanticsTest` convention), so this test pins:
 *  (a) the structural canary [brassProgressTrackExposesSeekSemantics],
 *      which must stay `true`, and
 *  (b) the pure seek-target math the semantics actions delegate to, so a
 *      regression in clamping/rounding is caught off-device.
 */
class BrassProgressTrackSemanticsTest {

    @Test
    fun `scrubber exposes a settable seek control to accessibility services per issue #1148`() {
        assertTrue(
            "BrassProgressTrack must expose progressBarRangeInfo + setProgress " +
                "+ ±30s custom actions so TalkBack / Switch Access users can seek (#1148)",
            brassProgressTrackExposesSeekSemantics,
        )
    }

    @Test
    fun `progress fraction maps position over duration into 0,,1`() {
        assertEquals(0f, brassProgressFraction(0, 100_000), 0f)
        assertEquals(0.5f, brassProgressFraction(50_000, 100_000), 1e-4f)
        assertEquals(1f, brassProgressFraction(100_000, 100_000), 0f)
    }

    @Test
    fun `progress fraction is clamped and safe when position exceeds or duration is unknown`() {
        // Over-run (position past the end) clamps to 1, never > 1.
        assertEquals(1f, brassProgressFraction(150_000, 100_000), 0f)
        // Unknown duration must not divide-by-zero — reports 0.
        assertEquals(0f, brassProgressFraction(5_000, 0), 0f)
        assertEquals(0f, brassProgressFraction(5_000, -1), 0f)
    }

    @Test
    fun `setProgress fraction converts to a clamped, rounded ms target`() {
        assertEquals(0L, brassSeekTargetMs(0f, 100_000))
        assertEquals(50_000L, brassSeekTargetMs(0.5f, 100_000))
        assertEquals(100_000L, brassSeekTargetMs(1f, 100_000))
        // Out-of-range fractions clamp into the chapter.
        assertEquals(0L, brassSeekTargetMs(-0.3f, 100_000))
        assertEquals(100_000L, brassSeekTargetMs(1.5f, 100_000))
        // Degenerate duration can't produce a negative/overshoot target.
        assertEquals(0L, brassSeekTargetMs(0.5f, 0))
    }

    @Test
    fun `skip custom actions move +-30s and clamp to chapter bounds`() {
        // Mid-chapter: clean ±30s.
        assertEquals(70_000L, brassSkipTargetMs(40_000, 100_000, SCRUBBER_SKIP_MS))
        assertEquals(10_000L, brassSkipTargetMs(40_000, 100_000, -SCRUBBER_SKIP_MS))
        // Skip-back near the start floors at 0, never negative.
        assertEquals(0L, brassSkipTargetMs(10_000, 100_000, -SCRUBBER_SKIP_MS))
        // Skip-forward near the end caps at duration, never past it.
        assertEquals(100_000L, brassSkipTargetMs(90_000, 100_000, SCRUBBER_SKIP_MS))
    }

    @Test
    fun `skip step matches the player's 30 second Replay30 - Forward30 buttons`() {
        // If the player skip interval ever changes, this and the icons
        // must move together (#268). Pin the contract.
        assertEquals(30_000L, SCRUBBER_SKIP_MS)
    }
}
