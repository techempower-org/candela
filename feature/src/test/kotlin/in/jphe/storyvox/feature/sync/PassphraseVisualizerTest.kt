package `in`.jphe.storyvox.feature.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #500 — basic sanity guards on the curated decorative
 * passphrase word list. The visualizer is a Compose surface so a
 * full render check would need Robolectric; this test only locks
 * the *data* properties that affect copy and security.
 *
 * Why these checks matter:
 *  - Empty list would crash the visualizer's row layout (the
 *    onboarding card would render an empty brass frame).
 *  - Real-looking secrets in the demo list would mis-train users
 *    about what their actual passphrase will look like; the demo
 *    is decorative ONLY (see the kdoc on [DEMO_PASSPHRASE_WORDS]).
 *  - Profanity / accidental rude words would land in the
 *    first-launch experience.
 */
class PassphraseVisualizerTest {

    @Test
    fun `demo passphrase has at least three words`() {
        // Three+ words feel "passphrase-ish" — fewer and it could
        // read as a single magic incantation rather than the
        // multi-word reveal the visualizer is designed for.
        assertTrue(
            "expected >=3 words, got ${DEMO_PASSPHRASE_WORDS.size}",
            DEMO_PASSPHRASE_WORDS.size >= 3,
        )
    }

    @Test
    fun `demo passphrase fits the stagger budget`() {
        // The stagger animation runs at 240ms per word with a 360ms
        // reveal — at 6 words the last word starts revealing at
        // 1.44s and finishes at 1.8s. Anything longer feels sluggish
        // on the onboarding card. Six is the documented ceiling.
        assertTrue(
            "demo passphrase grew beyond the 6-word stagger ceiling " +
                "(would feel sluggish on first launch); trim or bump " +
                "the budget kdoc on [PassphraseVisualizer]",
            DEMO_PASSPHRASE_WORDS.size <= 6,
        )
    }

    @Test
    fun `demo passphrase words are non-empty and lowercase`() {
        // Lowercase only — EB Garamond italic at 20sp doesn't render
        // distinct case at the onboarding scale, and the reveal-by-
        // word vibe is "soft incantation," not "shouting."
        DEMO_PASSPHRASE_WORDS.forEach { word ->
            assertFalse("blank word in demo list", word.isBlank())
            assertTrue(
                "word '$word' should be lowercase",
                word == word.lowercase(),
            )
            // No spaces — the visualizer's Row.spacedBy provides
            // the gap between words; embedded spaces would split a
            // single Text into a visually broken pair.
            assertFalse(
                "word '$word' contains an embedded space — split it " +
                    "into two list entries instead",
                word.contains(' '),
            )
        }
    }
}
