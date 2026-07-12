package `in`.jphe.storyvox.feature

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1661 — proves the Compose-UI test toolchain for `:feature` is wired
 * (deferred from Voice Notes Phase 4). [createComposeRule] runs under
 * Robolectric as a plain JVM unit test (no emulator), so any real `:feature`
 * screen — e.g. a Notes surface — can be driven with `setContent { … }` +
 * `onNode*` / `assert*` from here on.
 *
 * - [GraphicsMode.Mode.NATIVE] is required for Compose to actually rasterise
 *   under Robolectric (the default LEGACY canvas can't).
 * - `@Config(sdk = [36])` pins the sandbox to the project's compileSdk.
 * - `testOptions { unitTests { isIncludeAndroidResources = true } }` (see
 *   `feature/build.gradle.kts`) gives the sandbox the merged resources +
 *   manifest the rule's host activity inflates.
 *
 * NOTE (project memory): CI **compiles** this (the Test Compile job) but the
 * SDK-36 Robolectric sandbox needs **JDK 21 to execute** (JDK 17 fails at
 * `classMethod` with "Android SDK 36 requires Java 21"). CI runs JDK 17 and
 * only compiles tests — so a green build proves the toolchain is wired;
 * *running* the Compose tests is a local-JDK21 step, not a merge gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ComposeUiToolchainSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composeRule_setsContent_andFindsNode() {
        composeRule.setContent {
            Text("compose-ui-toolchain-ok")
        }
        composeRule.onNodeWithText("compose-ui-toolchain-ok").assertIsDisplayed()
    }
}
