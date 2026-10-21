package `in`.jphe.storyvox.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** JP 2026-10-21: every TechEMPOWER surface links the newsletter sign-up; About gets a row for it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AboutNewsletterCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the newsletter row opens the sign-up page`() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) { AboutNewsletterCard() }
        }
        composeRule.onNodeWithText("Get our newsletter").performClick()
        assertEquals(listOf("https://techempower.org/newsletter"), opened)
    }
}
