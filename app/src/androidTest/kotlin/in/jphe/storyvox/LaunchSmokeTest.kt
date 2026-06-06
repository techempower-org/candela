package `in`.jphe.storyvox

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer 3 proof-of-life — the first instrumented (on-device) Compose UI
 * test in the project (#1088). It exists to prove the androidTest stack is
 * wired correctly end to end: the Compose BOM resolves on the androidTest
 * classpath, ui-test-junit4 + ui-test-manifest are present, the
 * AndroidJUnitRunner instantiates the real `@HiltAndroidApp` [StoryvoxApp]
 * (so the Hilt graph is the production graph — no test harness needed for a
 * launch-only smoke), and [MainActivity] reaches first frame without
 * crashing.
 *
 * Two device-launch obstacles had to be cleared for the activity to reach
 * the resumed state the Compose test registry reads (both verified by
 * inspecting `dumpsys window` / `dumpsys activity` while the test polled):
 *
 *  1. MainActivity is `launchMode="singleTask"` in production. ActivityScenario
 *     (which backs [createAndroidComposeRule]) can't instrument a singleTask
 *     activity — the system reparents it into its own task, so the test
 *     owner sees no composition. Fixed by a debug-variant manifest overlay
 *     (`app/src/debug/AndroidManifest.xml`) that flips launchMode to
 *     `standard`; the shipped `release` variant keeps singleTask.
 *
 *  2. MainActivity requests POST_NOTIFICATIONS at launch. On an ungranted
 *     device the system permission dialog (GrantPermissionsActivity) takes
 *     focus and PAUSES MainActivity, so the registry reports zero resumed
 *     roots ("No compose hierarchies found"). [grantNotifications] pre-grants
 *     the permission so no dialog appears and the activity stays resumed.
 *
 * Assertion target: the visible string "Library". It's the bottom-nav tab
 * label — a hardcoded enum constant in `core-ui`'s `BottomTabBar.HomeTab`
 * (not a string resource), so it's the most stable visible text on the
 * post-onboarding home surface. The shared test tablet (R83W80CAFZB) has
 * onboarding already completed, so the bottom bar is the cold-launch
 * landing surface.
 *
 * Assertion shape: the word "Library" renders in several places on the
 * cold-launch Library surface (the bottom-nav tab, the in-screen
 * Library/Reading tab, the screen title) — observed as 2-3 matching nodes,
 * some clickable. For a launch proof-of-life that's fine: we assert that at
 * least one "Library" node renders AND is displayed, rather than
 * over-specifying which one. `onNodeWithText`/`hasClickAction` both threw
 * "expected at most 1 node" against the real surface.
 *
 * TODO(#1088 follow-up): assert a single, semantically-meaningful node via a
 * testTag once Lyra's Layer-0 PR lands testTags on the core navigable
 * surface — text matching is brittle to copy changes and locale, and can't
 * disambiguate the duplicate "Library" labels; a stable tag fixes both.
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {

    // Pre-grant POST_NOTIFICATIONS so the system permission dialog never
    // pops at launch (see class kdoc, obstacle #2). On API < 33 the
    // permission doesn't exist and GrantPermissionRule is a no-op.
    @get:Rule(order = 0)
    val grantNotifications: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launches_andShowsLibraryTab() {
        // Compose may still be settling first frame / running the cold-launch
        // animations when the rule hands control back. Wait for the nav bar
        // to render rather than asserting eagerly — the bottom bar mounts
        // after the VoicePickerGate resolves.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Library").fetchSemanticsNodes().isNotEmpty()
        }

        // Proof-of-life: at least one "Library" node rendered and is on
        // screen. Assert the first match is displayed (see kdoc on why we
        // don't over-specify which "Library" node before testTags land).
        composeRule.onAllNodesWithText("Library")[0].assertIsDisplayed()
    }
}
