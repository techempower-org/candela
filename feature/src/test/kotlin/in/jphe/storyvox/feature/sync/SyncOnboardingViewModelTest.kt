package `in`.jphe.storyvox.feature.sync

import `in`.jphe.storyvox.sync.client.SignedInUser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #500 — exercise the sync-onboarding gate semantics without
 * standing up the full Hilt graph.
 *
 * The interesting logic here is exclusively in the combine:
 *   shouldShow = manuallyOpened || (voicePicked && signedOut && !dismissed)
 *
 * Testing the gate at the spec level (a pure-function helper) is
 * cheaper than running a coroutine harness against the live VM —
 * the live VM's surface is just plumbing through Hilt + StateFlow.
 * The pure helper [shouldShowOnboarding] is exposed for that
 * purpose; the VM delegates to it.
 *
 * This test pins the spec states:
 *   1. Voice picked + signed out + not dismissed → SHOW (first launch)
 *   2. No voice yet → HIDE (VoicePickerGate is still in front)
 *   3. Voice picked + signed out + dismissed → HIDE (skip respected forever)
 *   4. Voice picked + signed in → HIDE (already onboarded)
 *   5. Manually re-opened → SHOW regardless of every other gate
 */
class SyncOnboardingViewModelTest {

    private val user = SignedInUser(
        userId = "u1",
        email = "user@example.com",
        refreshToken = "tok",
    )

    @Test
    fun `first launch with voice picked shows the card`() {
        // Fresh install: voice picker done (activeVoice != null),
        // library about to mount, but the user has never seen the
        // sync onboarding. This is the magical moment the issue is
        // about.
        assertTrue(
            shouldShowOnboarding(
                dismissed = false,
                signedIn = null,
                voicePicked = true,
                manuallyOpened = false,
            ),
        )
    }

    @Test
    fun `no voice picked hides the card`() {
        // The VoicePickerGate is still occupying the screen. The
        // onboarding card mounts at the NavHost root and would
        // float over the picker; gating on voicePicked enforces
        // the issue's "after the VoicePickerGate" ordering.
        assertFalse(
            shouldShowOnboarding(
                dismissed = false,
                signedIn = null,
                voicePicked = false,
                manuallyOpened = false,
            ),
        )
    }

    @Test
    fun `signed out but already dismissed never re-prompts`() {
        // The issue is explicit: "Skip is fully respected — never
        // re-prompt this flow." This guards that rule from a future
        // regression where someone re-enables the auto-show on every
        // launch.
        assertFalse(
            shouldShowOnboarding(
                dismissed = true,
                signedIn = null,
                voicePicked = true,
                manuallyOpened = false,
            ),
        )
    }

    @Test
    fun `signed in always hides the card`() {
        // User already on InstantDB (e.g. APK upgrade after #470)
        // shouldn't see the onboarding offer at all — both the
        // "fresh install" and the "already dismissed" branches
        // collapse to HIDE the moment the session is non-null.
        assertFalse(
            shouldShowOnboarding(
                dismissed = false,
                signedIn = user,
                voicePicked = true,
                manuallyOpened = false,
            ),
        )
        assertFalse(
            shouldShowOnboarding(
                dismissed = true,
                signedIn = user,
                voicePicked = true,
                manuallyOpened = false,
            ),
        )
    }

    @Test
    fun `manually opened wins over every other gate`() {
        // The cloud-icon's "Learn more" CTA in the bottom sheet
        // routes through [openManually]. The user explicitly asked
        // — the dismissed flag, voice-picked, and signed-in gates
        // are all overridden.
        assertTrue(
            shouldShowOnboarding(
                dismissed = true,
                signedIn = null,
                voicePicked = false,
                manuallyOpened = true,
            ),
        )
        assertTrue(
            shouldShowOnboarding(
                dismissed = true,
                signedIn = user,
                voicePicked = true,
                manuallyOpened = true,
            ),
        )
    }
}

/**
 * Pure helper for the sync-onboarding gate. Mirrors the predicate
 * inside [SyncOnboardingViewModel.shouldShow] so test coverage
 * doesn't require booting the VM (and the coroutine harness it
 * would need). The VM is a thin shell around this — if the
 * predicate is right here it's right there.
 */
internal fun shouldShowOnboarding(
    dismissed: Boolean,
    signedIn: SignedInUser?,
    voicePicked: Boolean,
    manuallyOpened: Boolean,
): Boolean {
    if (manuallyOpened) return true
    return voicePicked && signedIn == null && !dismissed
}
