package `in`.jphe.storyvox.ui.component

/**
 * Stable, kebab-case test-tag identifiers for the core navigable Compose
 * surface — the addressing layer the UI-test stack (Maestro flows +
 * instrumented Compose tests) selects by.
 *
 * **Why a constants object instead of inline string literals**: UI-test
 * selectors are a contract between the app and the test suite. A pixel-
 * or copy-based selector breaks the moment a layout shifts or a label is
 * reworded; a `testTag` survives both. Centralizing the strings here means
 * (a) the test suite imports the same symbol the UI sets, so a rename is a
 * compile error rather than a silently-broken flow, and (b) the tags are
 * discoverable in one place rather than scattered across screen files.
 *
 * **Convention**: kebab-case, `<area>-<element>` (e.g. `nav-browse`,
 * `library-fab`, `reader-body`). Tags are append-only and stable — adding
 * is free; renaming or removing one is a breaking change for downstream
 * Maestro flows and must be coordinated.
 *
 * Set via `Modifier.testTag(TestTags.NavBrowse)`. These are **non-functional**
 * — they only populate the Compose semantics tree and have zero effect on
 * behavior, layout, or accessibility (a11y announcements come from
 * `contentDescription` / `semantics`, which are untouched).
 */
object TestTags {
    // ── Bottom-nav destinations ──────────────────────────────────────────
    // One per HomeTab entry. Derived from the tab name so the dock and the
    // selectors can't drift; see [navTab].
    const val NavPlaying = "nav-playing"
    const val NavLibrary = "nav-library"
    const val NavBrowse = "nav-browse"
    const val NavVoices = "nav-voices"
    const val NavSettings = "nav-settings"

    /**
     * Stable nav tag for a [HomeTab], e.g. `HomeTab.Browse` → `"nav-browse"`.
     * The dock applies this per cell so a flow can tap a destination by
     * `nav-<tab>` without depending on cell order or visible label.
     */
    fun navTab(tab: HomeTab): String = "nav-" + tab.name.lowercase()

    // ── Library ──────────────────────────────────────────────────────────
    const val LibraryList = "library-list"
    const val LibraryItem = "library-item"
    const val LibraryFab = "library-fab"

    // ── Browse ───────────────────────────────────────────────────────────
    const val BrowseSearchField = "browse-search-field"
    const val BrowseSourceList = "browse-source-list"
    const val BrowseResults = "browse-results"
    const val BrowseFilter = "browse-filter"

    /**
     * Per-source chip in the Browse carousel, e.g. `SourceIds.RADIO` →
     * `"browse-source-radio"`. Lets an on-device flow (uiautomator/Maestro)
     * select a specific source by id rather than by carousel position or
     * visible label. (#1333)
     */
    fun browseSourceChip(sourceId: String): String = "browse-source-$sourceId"

    /**
     * Per-result card in the Browse grid, keyed by fiction id, e.g.
     * `"browse-result-rr:12345"`. Lets a flow open a specific result. (#1333)
     */
    fun browseResultCard(fictionId: String): String = "browse-result-$fictionId"

    // ── Source Catalog (#1365) ────────────────────────────────────────────
    /** The Browse → "Source Library" catalog list. */
    const val SourceCatalogList = "source-catalog-list"

    /** The entry-point affordance below the Browse carousel that opens the
     *  Source Catalog. */
    const val SourceCatalogEntry = "source-catalog-entry"

    /** Per-source card in the catalog, keyed by source id, e.g.
     *  `SourceIds.ARXIV` → `"source-catalog-card-arxiv"`. */
    fun sourceCatalogCard(sourceId: String): String = "source-catalog-card-$sourceId"

    // ── Reader ───────────────────────────────────────────────────────────
    const val ReaderBody = "reader-body"
    const val ReaderPlay = "reader-play"
    const val ReaderBack = "reader-back"

    // ── Reader in-book search (#1229) ─────────────────────────────────────
    // The whole-book find flow: a top-bar magnifying glass ([ReaderBookSearch])
    // opens the [BookSearchOverlay], whose field ([BookSearchField]) drives a
    // results list ([BookSearchResults]); tapping a result jumps to that
    // chapter. [BookSearchClose] dismisses the overlay.
    const val ReaderBookSearch = "reader-book-search"
    const val BookSearchOverlay = "book-search-overlay"
    const val BookSearchField = "book-search-field"
    const val BookSearchResults = "book-search-results"
    const val BookSearchClose = "book-search-close"

    // ── Reader highlights (#1079 phase 2) ────────────────────────────────
    // The select-text → highlight flow. A Maestro flow drives: drag-select
    // body text → [HighlightSheet] appears → pick a swatch in
    // [HighlightPalette] (+ optional [HighlightNoteField]) → [HighlightConfirm];
    // then tap a saved highlight → [HighlightEditSheet] → [HighlightSave] or
    // [HighlightDelete]. Tagged on the sheet container + each control so the
    // flow selects them without depending on visible copy.
    const val HighlightSheet = "highlight-sheet"
    const val HighlightEditSheet = "highlight-edit-sheet"
    const val HighlightPalette = "highlight-palette"
    const val HighlightNoteField = "highlight-note-field"
    const val HighlightConfirm = "highlight-confirm"
    const val HighlightSave = "highlight-save"
    const val HighlightDelete = "highlight-delete"
    // #1234 — share a quote: the toolbar icon opens a menu with share-sheet +
    // copy-to-clipboard items.
    const val HighlightShare = "highlight-share"
    const val HighlightShareSend = "highlight-share-send"
    const val HighlightShareCopy = "highlight-share-copy"

    // ── Fiction detail (#1333) ───────────────────────────────────────────
    // Action surface on the FictionDetail screen, so on-device QA can drive
    // add-to-library / resume / open without depending on visible copy.
    const val FictionDetailBack = "fiction-detail-back"
    const val FictionDetailAddToLibrary = "fiction-detail-add-to-library"
    const val FictionDetailResume = "fiction-detail-resume"
    const val FictionDetailReadMore = "fiction-detail-read-more"
    const val FictionDetailMenu = "fiction-detail-menu"

    // ── Voices ───────────────────────────────────────────────────────────
    const val VoiceList = "voice-list"

    // ── Settings ─────────────────────────────────────────────────────────
    const val SettingsList = "settings-list"

    // ── Common dialogs / sheets ──────────────────────────────────────────
    const val DialogConfirm = "dialog-confirm"
    const val DialogDismiss = "dialog-dismiss"
}
