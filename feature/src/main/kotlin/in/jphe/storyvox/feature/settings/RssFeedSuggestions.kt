package `in`.jphe.storyvox.feature.settings

import `in`.jphe.storyvox.feature.api.SuggestedFeed
import `in`.jphe.storyvox.feature.api.SuggestedFeedKind

// SuggestedFeed + SuggestedFeedKind moved to feature/api/UiContracts.kt
// in #246 so the SettingsRepositoryUi interface can expose them.

/**
 * Baked-in fallback list (#246) — used when the candela-feeds remote
 * fetch hasn't completed yet (cold start, offline) or when parsing the
 * JSON fails. Kept lean — just enough to make Settings → Suggested
 * feeds non-empty on first launch. The remote registry at
 * github.com/techempower-org/candela-feeds is the source of truth for
 * additions / changes; this list rarely needs editing.
 */
val BAKED_IN_SUGGESTED_FEEDS: List<SuggestedFeed> = listOf(
    // ── Buddhist & dharma ────────────────────────────────────────
    SuggestedFeed(
        title = "Tricycle: The Buddhist Review",
        description = "Long-form essays, philosophy, book reviews. Daily-ish.",
        url = "https://tricycle.org/feed/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.Text,
    ),
    SuggestedFeed(
        title = "Lion's Roar",
        description = "Contemporary Buddhist wisdom, mindfulness + practice.",
        url = "https://www.lionsroar.com/feed/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.Text,
    ),
    SuggestedFeed(
        title = "Buddhistdoor Global",
        description = "Asian Buddhist news + cultural analysis.",
        url = "https://www.buddhistdoor.net/feed/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.Text,
    ),
    SuggestedFeed(
        title = "Dharma Seed",
        description = "Theravada vipassana + metta talks (Spirit Rock + IMS).",
        url = "https://dharmaseed.org/feeds/recordings/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.AudioPodcast,
    ),
    SuggestedFeed(
        title = "AudioDharma",
        description = "Insight Meditation Center dharma talks (Gil Fronsdal).",
        url = "https://feeds.feedburner.com/audiodharma",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.AudioPodcast,
    ),
    SuggestedFeed(
        title = "Plum Village dharma talks",
        description = "Thich Nhat Hanh tradition.",
        url = "https://plumvillage.org/feed/audio/dharma-talks/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.AudioPodcast,
    ),
    SuggestedFeed(
        title = "Plum Village — The Way Out Is In",
        description = "Brother Phap Huu + Jo Confino podcast series.",
        url = "https://plumvillage.org/feed/audio/the-way-out-is-in/",
        category = "Buddhist & dharma",
        kind = SuggestedFeedKind.AudioPodcast,
    ),
)
