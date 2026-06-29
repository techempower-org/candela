package `in`.jphe.storyvox.feature.onboarding

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1370 — unit tests for the pure grouping layer of the onboarding
 * source picker. The Compose rendering follows the established
 * onboarding-screen pattern; this file covers the by-intent bucketing
 * that re-shapes the raw [SourceCategory] roster into the five friendly
 * sections, which is the only piece with real branching logic.
 */
class SourcePickerGroupingTest {

    private fun row(
        id: String,
        displayName: String = id,
        description: String = "",
        category: SourceCategory = SourceCategory.Text,
        enabled: Boolean = false,
    ) = SourcePickRow(
        id = id,
        displayName = displayName,
        description = description,
        category = category,
        enabled = enabled,
    )

    // ─── onboardingGroupOf ──────────────────────────────────────────

    @Test fun `audio stream category buckets under Audio regardless of id`() {
        val r = row(id = "some-unknown-stream", category = SourceCategory.AudioStream)
        assertEquals(OnboardingSourceGroup.Audio, onboardingGroupOf(r))
    }

    @Test fun `explicit ebook category buckets under Books`() {
        val r = row(id = "bookshare", category = SourceCategory.Ebook)
        assertEquals(OnboardingSourceGroup.Books, onboardingGroupOf(r))
    }

    @Test fun `curated book ids bucket under Books despite Text category`() {
        // Gutenberg, Standard Ebooks, EPUB and PDF are all annotated
        // SourceCategory.Text in their @SourcePlugin descriptors — the
        // curated id set is what pulls them into Books.
        listOf(SourceIds.GUTENBERG, SourceIds.STANDARD_EBOOKS, SourceIds.EPUB, SourceIds.PDF)
            .forEach { id ->
                assertEquals(
                    "expected $id under Books",
                    OnboardingSourceGroup.Books,
                    onboardingGroupOf(row(id = id)),
                )
            }
    }

    @Test fun `web fiction ids bucket under Web Fiction`() {
        listOf(SourceIds.ROYAL_ROAD, SourceIds.AO3).forEach { id ->
            assertEquals(OnboardingSourceGroup.WebFiction, onboardingGroupOf(row(id = id)))
        }
    }

    @Test fun `news and research ids bucket under News`() {
        listOf(
            SourceIds.WIKIPEDIA,
            SourceIds.HACKERNEWS,
            SourceIds.ARXIV,
            SourceIds.PLOS,
            SourceIds.GOOGLE_NEWS,
        ).forEach { id ->
            assertEquals(OnboardingSourceGroup.News, onboardingGroupOf(row(id = id)))
        }
    }

    @Test fun `uncurated text source falls through to Your Content`() {
        val r = row(id = "rss", category = SourceCategory.Text)
        assertEquals(OnboardingSourceGroup.YourContent, onboardingGroupOf(r))
    }

    @Test fun `unknown future source still lands in a real bucket`() {
        // A backend nobody listed yet must not vanish from the picker.
        val r = row(id = "brand-new-backend-2099", category = SourceCategory.Text)
        assertEquals(OnboardingSourceGroup.YourContent, onboardingGroupOf(r))
    }

    // ─── groupSourcesForOnboarding ──────────────────────────────────

    @Test fun `sections render in group display order`() {
        val rows = listOf(
            row(id = SourceIds.RADIO, category = SourceCategory.AudioStream),
            row(id = SourceIds.RSS),
            row(id = SourceIds.WIKIPEDIA),
            row(id = SourceIds.ROYAL_ROAD),
            row(id = SourceIds.GUTENBERG),
        )
        val sections = groupSourcesForOnboarding(rows)
        assertEquals(
            listOf(
                OnboardingSourceGroup.Books,
                OnboardingSourceGroup.WebFiction,
                OnboardingSourceGroup.News,
                OnboardingSourceGroup.YourContent,
                OnboardingSourceGroup.Audio,
            ),
            sections.map { it.group },
        )
    }

    @Test fun `empty groups are dropped`() {
        // Only News + Audio populated — Books, Web Fiction, Your Content
        // must not produce empty header sections.
        val rows = listOf(
            row(id = SourceIds.ARXIV),
            row(id = SourceIds.RADIO, category = SourceCategory.AudioStream),
        )
        val sections = groupSourcesForOnboarding(rows)
        assertEquals(
            listOf(OnboardingSourceGroup.News, OnboardingSourceGroup.Audio),
            sections.map { it.group },
        )
        assertTrue(sections.all { it.rows.isNotEmpty() })
    }

    @Test fun `rows within a section are alphabetised by display name`() {
        val rows = listOf(
            row(id = SourceIds.STANDARD_EBOOKS, displayName = "Standard Ebooks"),
            row(id = SourceIds.GUTENBERG, displayName = "Project Gutenberg"),
            row(id = SourceIds.EPUB, displayName = "Local EPUB files"),
        )
        val books = groupSourcesForOnboarding(rows).single { it.group == OnboardingSourceGroup.Books }
        assertEquals(
            listOf("Local EPUB files", "Project Gutenberg", "Standard Ebooks"),
            books.rows.map { it.displayName },
        )
    }

    @Test fun `enabled state is carried through grouping unchanged`() {
        val rows = listOf(
            row(id = SourceIds.ROYAL_ROAD, displayName = "Royal Road", enabled = true),
            row(id = SourceIds.AO3, displayName = "AO3", enabled = false),
        )
        val webFiction = groupSourcesForOnboarding(rows)
            .single { it.group == OnboardingSourceGroup.WebFiction }
        assertEquals(true, webFiction.rows.single { it.id == SourceIds.ROYAL_ROAD }.enabled)
        assertEquals(false, webFiction.rows.single { it.id == SourceIds.AO3 }.enabled)
    }

    @Test fun `empty input yields no sections`() {
        assertTrue(groupSourcesForOnboarding(emptyList()).isEmpty())
    }
}
