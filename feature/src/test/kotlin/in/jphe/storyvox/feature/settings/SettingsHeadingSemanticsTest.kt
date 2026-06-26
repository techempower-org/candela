package `in`.jphe.storyvox.feature.settings

import `in`.jphe.storyvox.feature.settings.components.sectionHeadingMarksLabelAsHeading
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1136 — regression guard for Settings heading semantics.
 *
 * Before #1136, `heading()` appeared 0× across the whole feature module, so
 * TalkBack's heading-navigation gesture found nothing — brutal on the
 * ~4,100-line legacy [SettingsScreen] and every dedicated subscreen. The
 * fix marks the shared title/section components as headings:
 *
 *  - [SettingsSubscreenScaffold] — TopAppBar title, backs every subscreen
 *  - [SettingsSectionHeader] (SettingsComposables) — legacy section header
 *  - `SectionHeading` (components) — newer section header w/ descriptor
 *
 * The :feature module ships JVM unit tests only (no Robolectric /
 * ComposeTestRule — see [SettingsSubscreenContractTest]), so we can't
 * assert the rendered semantics tree. These structural-marker constants
 * are the cheapest regression net; dropping `heading()` from a component
 * without flipping its marker (and re-verifying on-device with TalkBack)
 * fails this suite.
 */
class SettingsHeadingSemanticsTest {

    @Test
    fun `subscreen scaffold marks its title as a heading`() {
        assertTrue(
            "SettingsSubscreenScaffold's TopAppBar title must carry heading() so " +
                "TalkBack reaches each subscreen title via heading-navigation",
            settingsSubscreenScaffoldMarksTitleAsHeading,
        )
    }

    @Test
    fun `legacy SettingsSectionHeader marks its label as a heading`() {
        assertTrue(
            "SettingsSectionHeader's label must carry heading() so section headers " +
                "on the legacy long-scroll Settings screen are heading stops",
            settingsSectionHeaderMarksLabelAsHeading,
        )
    }

    @Test
    fun `SectionHeading marks its label as a heading`() {
        assertTrue(
            "SectionHeading's label must carry heading() so newer settings " +
                "section headers are reachable by heading-navigation",
            sectionHeadingMarksLabelAsHeading,
        )
    }
}
