package `in`.jphe.storyvox.navigation

import `in`.jphe.storyvox.ui.component.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restructure (v0.5.40 + v0.5.48 partial revert + v0.5.72 Browse
 * promotion) — bottom-nav + primary-destination contract.
 *
 * v0.5.40 directive: "put settings in the main nav bar, and put follows
 * and browse into the library tab." Collapsed bottom nav to two primary
 * destinations (Library + Settings) and demoted Browse / Follows / Playing
 * / Voices to deep routes reached from inside Library (sub-tabs) or
 * drill-down.
 *
 * v0.5.48 partial revert (JP feedback 2026-05-15): Playing + Voices
 * restored as primary destinations. Browse + Follows stayed as Library
 * sub-tabs. Dock was `Playing | Library | Voices | Settings`.
 *
 * v0.5.72 — Browse promoted to first-class bottom-nav destination
 * (compass icon, magical hero source carousel inside). Dock is now
 * `Playing | Library | Browse | Voices | Settings` — five tabs.
 * Follows stays under the Library umbrella (per-user scope).
 *
 * These tests pin the contract so a future refactor that removes the
 * Browse pill or changes the dock ordering fails here first. Plain
 * JUnit (no Robolectric): we're only inspecting enum + route-string
 * state, not any Android framework objects.
 */
class NavStructureTest {

    @Test
    fun `bottom nav exposes exactly five primary destinations`() {
        // Phone dock = the tabs with inBottomBar=true. Voice Notes (#1657)
        // kept the bar at five: Notes is RAIL-ONLY. Notes still lives in
        // HomeTab (so HomeTab.entries is 6, driving the tablet SideNavRail),
        // but it's excluded from the phone BottomTabBar — six pills crowd the
        // Flip3's ~260 dp cover. Phones reach Notes via the Library top-bar
        // waveform action instead (onOpenNotes → NOTES). See the `rail-only`
        // and `Library top-bar` tests below.
        assertEquals(5, HomeTab.entries.count { it.inBottomBar })
        // The rail still carries all six (the five dock tabs + Notes).
        assertEquals(6, HomeTab.entries.size)
    }

    @Test
    fun `bottom nav primary destinations are Playing Library Browse Voices Settings`() {
        // Order matters — BottomTabBar uses ordinal to position the indicator
        // pill. Playing leads (most-touched during a listening session);
        // Library second (cold-launch landing); Browse third (discovery
        // between "your shelves" and "playback ops"); Voices fourth; Settings
        // always last. (Notes is rail-only, not in this dock — see below.)
        val bottomBar = HomeTab.entries.filter { it.inBottomBar }.map { it.label }
        assertEquals(listOf("Playing", "Library", "Browse", "Voices", "Settings"), bottomBar)
        assertEquals(HomeTab.Playing, HomeTab.entries.first())
        assertEquals(HomeTab.Settings, HomeTab.entries.last())
    }

    @Test
    fun `Notes is a rail-only destination, not a phone bottom-bar tab`() {
        // #1657 — Notes stays first-class via the tablet SideNavRail + the
        // NOTES route, but is deliberately excluded from the phone
        // BottomTabBar (indicator-pill density on the Flip3 cover). Phones
        // reach it via the Library top-bar waveform action (onOpenNotes). This
        // pins the decision so re-adding Notes to the dock is a conscious change.
        assertTrue("Notes still exists for the rail", HomeTab.entries.contains(HomeTab.Notes))
        assertFalse("Notes is excluded from the phone dock", HomeTab.Notes.inBottomBar)
    }

    @Test
    fun `every non-Notes tab still renders in the phone bottom bar`() {
        // The rail-only carve-out is Notes and Notes alone — every other
        // HomeTab must stay a phone dock pill. Guards against a future tab
        // accidentally inheriting inBottomBar=false and vanishing from phones.
        HomeTab.entries.filter { it != HomeTab.Notes }.forEach {
            assertTrue("${it.name} must render in the phone bottom bar", it.inBottomBar)
        }
    }

    @Test
    fun `Notes is a home route so the nav surface stays visible`() {
        // NOTES must stay in HOME_ROUTES so the tablet SideNavRail (gated on
        // showHomeNav) renders on the Notes screen — dropping it would strand
        // the rail. On phones the bottom bar also stays visible there. The
        // NOTES constant is the target of both the rail pill and the Library
        // top-bar waveform action, so pin it too.
        assertTrue(StoryvoxRoutes.isHome(StoryvoxRoutes.NOTES))
        assertEquals("notes", StoryvoxRoutes.NOTES)
    }

    @Test
    fun `Browse is now a first-class bottom-nav destination (v0_5_72)`() {
        // Pinned alongside the order assertion above so a regression
        // that drops Browse from the dock fails this test by name (not
        // just the order assertion). Browse was a Library sub-tab in
        // v0.5.40–v0.5.71; v0.5.72 gave it its own pill with the
        // compass icon and a magical hero source carousel inside.
        assertNotNull(HomeTab.entries.firstOrNull { it == HomeTab.Browse })
        assertEquals("Browse", HomeTab.Browse.label)
    }

    @Test
    fun `Settings hub is a routable destination`() {
        // The Settings pill in the bottom bar navigates to
        // SETTINGS_HUB (the v0.5.38 hub screen with section cards),
        // not the legacy long-scroll SETTINGS page. If anyone wires
        // the pill to SETTINGS directly, the user lands in the wrong
        // place — pin the constant so the route-string survives.
        assertNotNull(StoryvoxRoutes.SETTINGS_HUB)
        assertTrue(
            "SETTINGS_HUB route must be non-empty",
            StoryvoxRoutes.SETTINGS_HUB.isNotEmpty(),
        )
    }

    @Test
    fun `Browse route still exists for deep-link resolution`() {
        // Browse was demoted to a Library sub-tab, but the standalone
        // BROWSE route stays in the nav graph so deep-links (e.g. the
        // HybridReader empty-state "Browse the realms" CTA) keep
        // resolving. If anyone deletes the BROWSE constant, every
        // existing deep-linker silently breaks.
        assertNotNull(StoryvoxRoutes.BROWSE)
        assertEquals("browse", StoryvoxRoutes.BROWSE)
    }

    @Test
    fun `Follows route still exists for deep-link resolution`() {
        // Same demotion + survival contract as BROWSE.
        assertNotNull(StoryvoxRoutes.FOLLOWS)
        assertEquals("follows", StoryvoxRoutes.FOLLOWS)
    }

    @Test
    fun `Library is a home route (bottom nav stays visible)`() {
        // Landing destination — bottom bar visible.
        assertTrue(StoryvoxRoutes.isHome(StoryvoxRoutes.LIBRARY))
    }

    @Test
    fun `Settings hub is a home route (bottom nav stays visible)`() {
        // Second primary destination — bottom bar visible.
        assertTrue(StoryvoxRoutes.isHome(StoryvoxRoutes.SETTINGS_HUB))
    }

    @Test
    fun `Browse and Follows are still home routes for the bottom bar`() {
        // Even though they're no longer bottom-bar *destinations*,
        // BROWSE and FOLLOWS are still "home depth" surfaces — when a
        // deep-link or back-stack push lands the user there, the
        // bottom bar must stay visible so the user can return to
        // Library or jump to Settings. The pill maps both to the
        // Library destination since they belong under the Library
        // umbrella now.
        assertTrue(StoryvoxRoutes.isHome(StoryvoxRoutes.BROWSE))
        assertTrue(StoryvoxRoutes.isHome(StoryvoxRoutes.FOLLOWS))
    }

    @Test
    fun `Reader and Audiobook show the bottom bar`() {
        // Issue #267 — Reader / Audiobook ARE the player surface,
        // reached via drill-down. The bottom bar stays visible there
        // (just like home routes) so the user can switch destinations
        // without backing out of the player. Pinned here so the
        // restructure didn't accidentally drop those entries from the
        // showsBottomNav set.
        assertTrue(StoryvoxRoutes.showsBottomNav(StoryvoxRoutes.READER))
        assertTrue(StoryvoxRoutes.showsBottomNav(StoryvoxRoutes.AUDIOBOOK))
    }

    @Test
    fun `Fiction detail is a drill-down (no bottom bar)`() {
        // Negative case — fiction detail is a stack-push, not a home
        // surface. The bottom bar hides so the user reads the bar's
        // visibility as a "you're at home" signal.
        assertFalse(StoryvoxRoutes.showsBottomNav(StoryvoxRoutes.FICTION_DETAIL))
    }
}
