package `in`.jphe.storyvox.feature.chat.tools

import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.repository.CachedBodyUsage
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.ShelfRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiAddByUrlResult
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiFollow
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiSigil
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.AzureProbeResult
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.llm.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #216 — handler-level behaviour tests for the v1 tool catalog.
 * These exercise the [ChatToolHandlers] private functions through their
 * `internal` visibility (same module), threading typed JSON args in and
 * inspecting the [ToolResult] back.
 *
 * Repos are tiny in-memory fakes — full coverage of the repo
 * implementations lives in their own test classes. The intent here is
 * "does the handler glue between args/repos do the right thing?", not
 * "does the repo work?".
 */
class ChatToolHandlersTest {

    @Test
    fun `add_to_shelf round-trips Reading shelf`() = runTest {
        val shelf = FakeShelfRepo()
        val handlers = makeHandlers(shelfRepo = shelf)
        val args = buildJsonObject {
            put("fictionId", "f1")
            put("shelf", "Reading")
        }
        val result = handlers.addToShelf(args)
        assertTrue(
            "Expected Success, got $result",
            result is ToolResult.Success,
        )
        assertEquals(setOf("f1" to Shelf.Reading), shelf.added)
        val success = result as ToolResult.Success
        assertTrue(
            "Success message should reference the fiction title (was ${success.message})",
            success.message.contains("Sky Pride"),
        )
        assertTrue(success.message.contains("Reading"))
    }

    @Test
    fun `add_to_shelf rejects unknown shelf with error`() = runTest {
        val shelf = FakeShelfRepo()
        val handlers = makeHandlers(shelfRepo = shelf)
        val args = buildJsonObject {
            put("fictionId", "f1")
            put("shelf", "Favorites")  // not one of the three
        }
        val result = handlers.addToShelf(args)
        assertTrue(
            "Expected Error, got $result",
            result is ToolResult.Error,
        )
        // No mutation should have happened.
        assertEquals(emptySet<Pair<String, Shelf>>(), shelf.added)
    }

    @Test
    fun `set_speed clamps to the allowed range`() = runTest {
        val playback = FakePlayback()
        val handlers = makeHandlers(playback = playback)
        // 4x is well past the 2.5 cap.
        val tooFast = handlers.setSpeed(buildJsonObject { put("speed", 4.0f) })
        assertTrue(tooFast is ToolResult.Success)
        assertEquals(2.5f, playback.appliedSpeed, 0.001f)
        assertTrue(
            (tooFast as ToolResult.Success).message.contains("clamped"),
        )
        // 0.1x is below the 0.5 floor.
        val tooSlow = handlers.setSpeed(buildJsonObject { put("speed", 0.1f) })
        assertTrue(tooSlow is ToolResult.Success)
        assertEquals(0.5f, playback.appliedSpeed, 0.001f)
    }

    @Test
    fun `set_speed in-range applies exactly`() = runTest {
        val playback = FakePlayback()
        val handlers = makeHandlers(playback = playback)
        val result = handlers.setSpeed(buildJsonObject { put("speed", 1.2f) })
        assertTrue(result is ToolResult.Success)
        assertEquals(1.2f, playback.appliedSpeed, 0.001f)
        assertTrue(
            !(result as ToolResult.Success).message.contains("clamped"),
        )
    }

    @Test
    fun `open_voice_library fires the navigation callback`() = runTest {
        var fired = 0
        val handlers = makeHandlers(onOpenVoiceLibrary = { fired++ })
        val result = handlers.openVoiceLibrary()
        assertTrue(result is ToolResult.Success)
        assertEquals(1, fired)
    }

    @Test
    fun `registry exposes every catalog tool by name`() {
        val handlers = makeHandlers()
        val registry = handlers.registry()
        assertEquals(7, registry.catalog.size)
        registry.catalog.forEach { spec ->
            assertNotNull(
                "Registry missing handler for ${spec.name}",
                registry.handler(spec.name),
            )
        }
    }

    // ── search_sources ─────────────────────────────────────────────

    @Test
    fun `search_sources aggregates hits across enabled sources`() = runTest {
        val rr = FakeSearchSource(
            "royalroad", "Royal Road",
            searchResult = pageOf(summary("rr1", "royalroad", "Wandering Inn", "pirateaba")),
        )
        val ao3 = FakeSearchSource(
            "ao3", "AO3",
            searchResult = pageOf(summary("ao1", "ao3", "All the Young Dudes", "MsKingBean89")),
        )
        val handlers = makeHandlers(sourceRegistry = registryOf(descriptorFor(rr), descriptorFor(ao3)))

        val result = handlers.searchSources(buildJsonObject { put("query", "magic") })
        assertTrue("Expected Success, got $result", result is ToolResult.Success)
        val msg = (result as ToolResult.Success).message
        assertTrue("Should list the RR hit (was $msg)", msg.contains("Wandering Inn"))
        assertTrue("Should list the AO3 hit (was $msg)", msg.contains("All the Young Dudes"))
        // The id/source tail is what lets the model chain into details.
        assertTrue("Row should carry the fiction id", msg.contains("id=rr1"))
        assertTrue("Row should carry the source id", msg.contains("source=ao3"))
    }

    @Test
    fun `search_sources skips a failing source and returns the rest`() = runTest {
        val dead = FakeSearchSource(
            "royalroad", "Royal Road",
            searchResult = FictionResult.AuthRequired(),
        )
        val live = FakeSearchSource(
            "gutenberg", "Gutenberg",
            searchResult = pageOf(summary("g1", "gutenberg", "Frankenstein", "Mary Shelley")),
        )
        val handlers = makeHandlers(sourceRegistry = registryOf(descriptorFor(dead), descriptorFor(live)))

        val result = handlers.searchSources(buildJsonObject { put("query", "gothic") })
        assertTrue("Auth-gated source must not sink the search", result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).message.contains("Frankenstein"))
    }

    @Test
    fun `search_sources throwing source is swallowed`() = runTest {
        val boom = FakeSearchSource(
            "boom", "Boom", searchResult = pageOf(), throwOnSearch = true,
        )
        val live = FakeSearchSource(
            "gutenberg", "Gutenberg",
            searchResult = pageOf(summary("g1", "gutenberg", "Dracula", "Bram Stoker")),
        )
        val handlers = makeHandlers(sourceRegistry = registryOf(descriptorFor(boom), descriptorFor(live)))

        val result = handlers.searchSources(buildJsonObject { put("query", "vampire") })
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).message.contains("Dracula"))
    }

    @Test
    fun `search_sources narrows to the named source`() = runTest {
        val rr = FakeSearchSource(
            "royalroad", "Royal Road",
            searchResult = pageOf(summary("rr1", "royalroad", "Only RR", "a")),
        )
        val ao3 = FakeSearchSource(
            "ao3", "AO3",
            searchResult = pageOf(summary("ao1", "ao3", "Only AO3", "b")),
        )
        val handlers = makeHandlers(sourceRegistry = registryOf(descriptorFor(rr), descriptorFor(ao3)))

        // Filter by display name (case-insensitive).
        val result = handlers.searchSources(
            buildJsonObject { put("query", "x"); put("source", "royal road") },
        )
        assertTrue(result is ToolResult.Success)
        val msg = (result as ToolResult.Success).message
        assertTrue(msg.contains("Only RR"))
        assertTrue("AO3 should be excluded by the filter", !msg.contains("Only AO3"))
    }

    @Test
    fun `search_sources caps the result count at limit`() = runTest {
        val many = (1..5).map { summary("g$it", "gutenberg", "Book $it", "auth") }
        val src = FakeSearchSource("gutenberg", "Gutenberg", searchResult = pageOf(*many.toTypedArray()))
        val handlers = makeHandlers(sourceRegistry = registryOf(descriptorFor(src)))

        val result = handlers.searchSources(
            buildJsonObject { put("query", "x"); put("limit", 2) },
        )
        assertTrue(result is ToolResult.Success)
        val msg = (result as ToolResult.Success).message
        assertTrue("Header should note the cap (was $msg)", msg.contains("showing 2"))
        // Exactly two bullet rows rendered.
        assertEquals(2, msg.lines().count { it.startsWith("•") })
    }

    @Test
    fun `search_sources errors on a blank query`() = runTest {
        val handlers = makeHandlers(
            sourceRegistry = registryOf(descriptorFor(FakeSearchSource("g", "G", pageOf()))),
        )
        val result = handlers.searchSources(buildJsonObject { put("query", "   ") })
        assertTrue(result is ToolResult.Error)
    }

    @Test
    fun `search_sources errors when no searchable source is enabled`() = runTest {
        // A registered source that doesn't advertise search is not a target.
        val noSearch = descriptorFor(FakeSearchSource("g", "G", pageOf()), supportsSearch = false)
        val handlers = makeHandlers(sourceRegistry = registryOf(noSearch))
        val result = handlers.searchSources(buildJsonObject { put("query", "x") })
        assertTrue(result is ToolResult.Error)
    }

    // ── get_book_details ───────────────────────────────────────────

    @Test
    fun `get_book_details routes to the named source and renders metadata`() = runTest {
        val detail = FictionDetail(
            summary = summary("g1", "gutenberg", "Moby Dick", "Herman Melville").copy(
                description = "A whale of a tale.",
                rating = 4.2f,
            ),
            chapters = listOf(chapterInfo("c1", 0), chapterInfo("c2", 1), chapterInfo("c3", 2)),
            genres = listOf("Adventure", "Classic"),
            wordCount = 206_052L,
        )
        val src = FakeSearchSource("gutenberg", "Gutenberg", pageOf(), detailResult = FictionResult.Success(detail))
        val handlers = makeHandlers(sourceRegistry = registryOf(descriptorFor(src)))

        val result = handlers.getBookDetails(
            buildJsonObject { put("fictionId", "g1"); put("source", "gutenberg") },
        )
        assertTrue("Expected Success, got $result", result is ToolResult.Success)
        val msg = (result as ToolResult.Success).message
        assertTrue(msg.contains("Moby Dick"))
        assertTrue("Chapter count from the live TOC", msg.contains("Chapters: 3"))
        assertTrue(msg.contains("Adventure"))
        assertTrue(msg.contains("A whale of a tale."))
    }

    @Test
    fun `get_book_details errors when no enabled source can resolve the id`() = runTest {
        val src = FakeSearchSource("gutenberg", "Gutenberg", pageOf()) // detail defaults to NotFound
        val handlers = makeHandlers(sourceRegistry = registryOf(descriptorFor(src)))
        val result = handlers.getBookDetails(buildJsonObject { put("fictionId", "ghost") })
        assertTrue(result is ToolResult.Error)
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun summary(id: String, sourceId: String, title: String, author: String) =
        FictionSummary(id = id, sourceId = sourceId, title = title, author = author)

    private fun chapterInfo(id: String, index: Int) =
        ChapterInfo(id = id, sourceChapterId = id, index = index, title = "Chapter ${index + 1}")

    private fun pageOf(vararg items: FictionSummary): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items.toList(), page = 1, hasNext = false))

    private fun descriptorFor(
        source: FictionSource,
        defaultEnabled: Boolean = true,
        supportsSearch: Boolean = true,
    ) = SourcePluginDescriptor(
        id = source.id,
        displayName = source.displayName,
        defaultEnabled = defaultEnabled,
        category = SourceCategory.Text,
        supportsFollow = false,
        supportsSearch = supportsSearch,
        source = source,
    )

    private fun registryOf(vararg descriptors: SourcePluginDescriptor) =
        SourcePluginRegistry(descriptors.toSet())

    private fun makeHandlers(
        shelfRepo: FakeShelfRepo = FakeShelfRepo(),
        chapterRepo: FakeChapterRepo = FakeChapterRepo(),
        fictionRepo: FakeFictionRepoT = FakeFictionRepoT(),
        playback: FakePlayback = FakePlayback(),
        settings: FakeSettings = FakeSettings(),
        sourceRegistry: SourcePluginRegistry = SourcePluginRegistry(emptySet()),
        onOpenVoiceLibrary: () -> Unit = {},
    ): ChatToolHandlers = ChatToolHandlers(
        activeFictionId = "f1",
        shelfRepo = shelfRepo,
        chapterRepo = chapterRepo,
        fictionRepo = fictionRepo,
        playback = playback,
        settingsRepo = settings,
        sourceRegistry = sourceRegistry,
        onOpenVoiceLibrary = onOpenVoiceLibrary,
    )
}

// ── Fakes ───────────────────────────────────────────────────────────

private class FakeShelfRepo : ShelfRepository {
    val added: MutableSet<Pair<String, Shelf>> = mutableSetOf()
    override fun observeByShelf(shelf: Shelf): Flow<List<FictionSummary>> = flowOf(emptyList())
    override fun observeShelvesForFiction(fictionId: String): Flow<Set<Shelf>> = flowOf(emptySet())
    override suspend fun shelvesForFiction(fictionId: String): Set<Shelf> = emptySet()
    override suspend fun add(fictionId: String, shelf: Shelf) {
        added += fictionId to shelf
    }
    override suspend fun remove(fictionId: String, shelf: Shelf) {
        added -= fictionId to shelf
    }
    override suspend fun clearForFiction(fictionId: String) {
        added.removeAll { it.first == fictionId }
    }
}

private class FakeChapterRepo : ChapterRepository {
    val readMarks: MutableList<Pair<String, Boolean>> = mutableListOf()
    override fun observeChapters(fictionId: String): Flow<List<ChapterInfo>> = flowOf(emptyList())
    override fun observeChapter(chapterId: String): Flow<ChapterContent?> = flowOf(null)
    override fun observeDownloadState(fictionId: String): Flow<Map<String, ChapterDownloadState>> = flowOf(emptyMap())
    override fun observePlayedChapterIds(fictionId: String): Flow<Set<String>> = flowOf(emptySet())
    // Issue #1189 — content-preview feed; chat tools don't surface previews.
    override fun observeChapterPreviews(fictionId: String): Flow<Map<String, String>> = flowOf(emptyMap())
    override suspend fun queueChapterDownload(fictionId: String, chapterId: String, requireUnmetered: Boolean) = Unit
    override suspend fun queueAllMissing(fictionId: String, requireUnmetered: Boolean) = Unit
    override suspend fun markRead(chapterId: String, read: Boolean) {
        readMarks += chapterId to read
    }
    override suspend fun markChapterPlayed(chapterId: String) {
        readMarks += chapterId to true
    }
    override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) = Unit
    override suspend fun getChapter(id: String): PlaybackChapter? = null
    override suspend fun getNextChapterId(currentChapterId: String): String? = null
    override suspend fun getPreviousChapterId(currentChapterId: String): String? = null
    override suspend fun cachedBodyUsage() = CachedBodyUsage(0, 0L)
    override suspend fun setChapterBookmark(chapterId: String, charOffset: Int?) = Unit
    override suspend fun chapterBookmark(chapterId: String): Int? = null
    override suspend fun searchChapterBodies(
        fictionId: String,
        query: String,
        limit: Int,
    ): List<`in`.jphe.storyvox.data.db.dao.ChapterSearchRow> = emptyList()
}

private class FakeFictionRepoT : FictionRepositoryUi {
    override val library: Flow<List<UiFiction>> = flowOf(emptyList())
    override val follows: Flow<List<UiFollow>> = flowOf(emptyList())
    override fun fictionById(id: String): Flow<UiFiction?> = flowOf(
        UiFiction(
            id = id,
            title = "Sky Pride",
            author = "Anon",
            coverUrl = null,
            rating = 0f,
            chapterCount = 0,
            isOngoing = true,
            synopsis = "",
        ),
    )
    override fun fictionLoadError(id: String): Flow<String?> = flowOf(null)
    override fun chaptersFor(fictionId: String): Flow<List<UiChapter>> = flowOf(emptyList())
    override fun observeIsInLibrary(fictionId: String): Flow<Boolean> = flowOf(false)
    override suspend fun chapterTextById(chapterId: String): String? = null
    override suspend fun setDownloadMode(fictionId: String, mode: DownloadMode) = Unit
    override suspend fun follow(fictionId: String, follow: Boolean) = Unit
    override suspend fun setFollowedRemote(fictionId: String, followed: Boolean): SetFollowedRemoteResult =
        SetFollowedRemoteResult.Success
    override suspend fun markAllCaughtUp() = 0
    override suspend fun refreshFollows() = Unit
    override suspend fun addByUrl(url: String, preferredSourceId: String?): UiAddByUrlResult = UiAddByUrlResult.UnrecognizedUrl
    override fun previewUrl(url: String) = emptyList<`in`.jphe.storyvox.feature.api.UiRouteCandidate>()
}

private class FakePlayback : PlaybackControllerUi {
    var appliedSpeed: Float = Float.NaN
    var started: Triple<String, String, Int>? = null
    override val state: Flow<UiPlaybackState> = MutableStateFlow(
        UiPlaybackState(
            fictionId = null, chapterId = null, chapterTitle = "", fictionTitle = "",
            coverUrl = null, isPlaying = false, positionMs = 0, durationMs = 0,
            sentenceStart = 0, sentenceEnd = 0, speed = 1f, pitch = 1f,
            voiceId = null, voiceLabel = "",
        ),
    )
    override val chapterText: Flow<String> = flowOf("")
    override val recapPlayback: Flow<UiRecapPlaybackState> = flowOf(UiRecapPlaybackState.Idle)
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(ms: Long) = Unit
    override fun seekToChar(charOffset: Int) = Unit
    override fun skipForward() = Unit
    override fun skipBack() = Unit
    override fun nextSentence() = Unit
    override fun previousSentence() = Unit
    override fun nextParagraph() = Unit
    override fun previousParagraph() = Unit
    override fun nextChapter() = Unit
    override fun previousChapter() = Unit
    override fun setSpeed(speed: Float) { appliedSpeed = speed }
    override fun setPitch(pitch: Float) = Unit
    override fun setPunctuationPauseMultiplier(multiplier: Float) = Unit
    override fun startListening(
        fictionId: String, chapterId: String, charOffset: Int, autoPlay: Boolean,
    ) { started = Triple(fictionId, chapterId, charOffset) }
    override fun startSleepTimer(mode: UiSleepTimerMode) = Unit
    override fun cancelSleepTimer() = Unit
    override suspend fun speakText(text: String) = Unit
    override fun stopSpeaking() = Unit
    override fun bookmarkHere() = Unit
    override fun clearBookmark() = Unit
    override fun jumpToBookmark() = Unit
}

/**
 * Same shape as ChatViewModelTest.FakeSettingsRepo — covers every
 * abstract method on [SettingsRepositoryUi] with a no-op. The
 * interface has a number of default-impl members we don't need to
 * override (markMilestoneDialogSeen, setInboxNotifyRoyalRoad, etc.).
 */
private class FakeSettings : SettingsRepositoryUi {
    override val settings: Flow<UiSettings> = flowOf(
        UiSettings(
            ttsEngine = "VoxSherpa",
            defaultVoiceId = null,
            defaultSpeed = 1f,
            defaultPitch = 1f,
            themeOverride = ThemeOverride.System,
            downloadOnWifiOnly = false,
            pollIntervalHours = 0,
            isSignedIn = false,
        ),
    )
    override val outlineHost: Flow<String> = flowOf("")
    override val rssSubscriptions: Flow<List<String>> = flowOf(emptyList())
    override val epubFolderUri: Flow<String?> = flowOf(null)
    override val pdfFolderUri: Flow<String?> = flowOf(null)
    override val suggestedRssFeeds: Flow<List<`in`.jphe.storyvox.feature.api.SuggestedFeed>> =
        flowOf(emptyList())
    override suspend fun setTheme(override: ThemeOverride) = Unit
    override suspend fun setDefaultSpeed(speed: Float) = Unit
    override suspend fun setDefaultPitch(pitch: Float) = Unit
    override suspend fun setDefaultVoice(voiceId: String?) = Unit
    override suspend fun setDownloadOnWifiOnly(enabled: Boolean) = Unit
    override suspend fun setPollIntervalHours(hours: Int) = Unit
    override suspend fun setPunctuationPauseMultiplier(multiplier: Float) = Unit
    override suspend fun setPitchInterpolationHighQuality(enabled: Boolean) = Unit
    override suspend fun setVoiceLexicon(voiceId: String, path: String?) = Unit
    override suspend fun setVoicePhonemizerLang(voiceId: String, langCode: String?) = Unit
    override suspend fun setPlaybackBufferChunks(chunks: Int) = Unit
    override suspend fun setWarmupWait(enabled: Boolean) = Unit
    override suspend fun setCatchupPause(enabled: Boolean) = Unit
    override suspend fun setFullPrerender(enabled: Boolean) = Unit
    override suspend fun setCacheQuotaBytes(bytes: Long) = Unit
    override suspend fun clearCache(): Long = 0L
    override suspend fun setVoiceSteady(enabled: Boolean) = Unit
    override suspend fun signIn() = Unit
    override suspend fun signOut() = Unit
    override suspend fun setPalaceHost(host: String) = Unit
    override suspend fun setPalaceApiKey(apiKey: String) = Unit
    override suspend fun clearPalaceConfig() = Unit
    override suspend fun testPalaceConnection(): PalaceProbeResult = PalaceProbeResult.NotConfigured
    override suspend fun setAiProvider(provider: UiLlmProvider?) = Unit
    override suspend fun setClaudeApiKey(key: String?) = Unit
    override suspend fun setClaudeModel(model: String) = Unit
    override suspend fun setOpenAiApiKey(key: String?) = Unit
    override suspend fun setOpenAiModel(model: String) = Unit
    override suspend fun setOllamaBaseUrl(url: String) = Unit
    override suspend fun setOllamaModel(model: String) = Unit
    override suspend fun setVertexApiKey(key: String?) = Unit
    override suspend fun setVertexModel(model: String) = Unit
    override suspend fun setVertexServiceAccountJson(json: String?) = Unit
    override suspend fun setFoundryApiKey(key: String?) = Unit
    override suspend fun setFoundryEndpoint(url: String) = Unit
    override suspend fun setFoundryDeployment(deployment: String) = Unit
    override suspend fun setFoundryServerless(serverless: Boolean) = Unit
    override suspend fun setBedrockAccessKey(key: String?) = Unit
    override suspend fun setBedrockSecretKey(key: String?) = Unit
    override suspend fun setBedrockRegion(region: String) = Unit
    override suspend fun setBedrockModel(model: String) = Unit
    override suspend fun setSendChapterTextEnabled(enabled: Boolean) = Unit
    override suspend fun setChatGroundChapterTitle(enabled: Boolean) = Unit
    override suspend fun setChatGroundCurrentSentence(enabled: Boolean) = Unit
    override suspend fun setChatGroundEntireChapter(enabled: Boolean) = Unit
    override suspend fun setChatGroundEntireBookSoFar(enabled: Boolean) = Unit
    override suspend fun setCarryMemoryAcrossFictions(enabled: Boolean) = Unit
    override suspend fun setAiActionsEnabled(enabled: Boolean) = Unit
    override suspend fun acknowledgeAiPrivacy() = Unit
    override suspend fun signOutTeams() = Unit
    override suspend fun resetAiSettings() = Unit
    override suspend fun signOutGitHub() = Unit
    override suspend fun setGitHubPrivateReposEnabled(enabled: Boolean) = Unit
    override suspend fun setSourcePluginEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun setSourceFavorite(id: String, favorite: Boolean) = Unit
    override suspend fun setSourceDisplayOrder(order: List<String>) = Unit
    override suspend fun setVoiceFamilyEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun setOutlineHost(host: String) = Unit
    override suspend fun setOutlineApiKey(apiKey: String) = Unit
    override suspend fun clearOutlineConfig() = Unit
    override suspend fun setWikipediaLanguageCode(code: String) = Unit
    override suspend fun setNotionDatabaseId(id: String) = Unit
    override suspend fun setNotionApiToken(token: String?) = Unit
    override suspend fun setDiscordApiToken(token: String?) = Unit
    override suspend fun setDiscordServer(serverId: String, serverName: String) = Unit
    override suspend fun setDiscordCoalesceMinutes(minutes: Int) = Unit
    override suspend fun fetchDiscordGuilds(): List<Pair<String, String>> = emptyList()
    override suspend fun setTelegramApiToken(token: String?) = Unit
    override suspend fun probeTelegramBot(): String? = null
    override suspend fun fetchTelegramChannels(): List<Pair<String, String>> = emptyList()
    override suspend fun addRssFeed(url: String) = Unit
    override suspend fun removeRssFeed(fictionId: String) = Unit
    override suspend fun removeRssFeedByUrl(url: String) = Unit
    override suspend fun setEpubFolderUri(uri: String) = Unit
    override suspend fun clearEpubFolder() = Unit
    override suspend fun setPdfFolderUri(uri: String) = Unit
    override suspend fun clearPdfFolder() = Unit
    override suspend fun setSleepShakeToExtendEnabled(enabled: Boolean) = Unit
    override suspend fun setAzureKey(key: String?) = Unit
    override suspend fun setAzureRegion(regionId: String) = Unit
    override suspend fun clearAzureCredentials() = Unit
    override suspend fun setParallelSynthInstances(count: Int) = Unit
    override suspend fun setSynthThreadsPerInstance(count: Int) = Unit
    override suspend fun setAzureFallbackEnabled(enabled: Boolean) = Unit
    override suspend fun setAzureFallbackVoiceId(voiceId: String?) = Unit
    override suspend fun testAzureConnection(): AzureProbeResult = AzureProbeResult.NotConfigured
}

/**
 * Issue #1227 — a [FictionSource] whose `search` / `fictionDetail` return
 * canned results, with a `throwOnSearch` escape hatch for the
 * skip-a-broken-backend path. Only the methods the catalog tools touch
 * carry behaviour; the rest are inert stubs.
 */
private class FakeSearchSource(
    override val id: String,
    override val displayName: String,
    private val searchResult: FictionResult<ListPage<FictionSummary>>,
    private val detailResult: FictionResult<FictionDetail> = FictionResult.NotFound("fake"),
    private val throwOnSearch: Boolean = false,
) : FictionSource {
    override suspend fun popular(page: Int) = emptyPage(page)
    override suspend fun latestUpdates(page: Int) = emptyPage(page)
    override suspend fun byGenre(genre: String, page: Int) = emptyPage(page)
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        if (throwOnSearch) error("boom")
        return searchResult
    }
    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> = detailResult
    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> =
        FictionResult.NotFound("fake")
    override suspend fun followsList(page: Int) = emptyPage(page)
    override suspend fun setFollowed(fictionId: String, followed: Boolean) = FictionResult.Success(Unit)
    override suspend fun genres() = FictionResult.Success(emptyList<String>())
    private fun emptyPage(page: Int) =
        FictionResult.Success(ListPage<FictionSummary>(items = emptyList(), page = page, hasNext = false))
}
