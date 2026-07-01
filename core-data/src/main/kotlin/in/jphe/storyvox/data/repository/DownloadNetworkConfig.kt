package `in`.jphe.storyvox.data.repository

/**
 * Issue #1461 — bridges the user's "download over Wi-Fi only" / data-saver
 * preference into `:core-data`'s chapter-download plumbing without dragging
 * the feature-layer `SettingsRepositoryUi` into this module.
 *
 * Mirrors the [`in`.jphe.storyvox.data.repository.playback.ParallelSynthConfig]
 * / [GoogleNewsConfig] pattern: this interface lives in `:core-data`, and
 * `SettingsRepositoryUiImpl` in `:app` implements it against the same DataStore
 * key (`pref_download_wifi_only`) that backs the Settings toggle.
 *
 * The value maps straight onto `requireUnmetered` in
 * [ChapterRepository.queueChapterDownload] / [ChapterRepository.queueAllMissing]:
 * `true` ⇒ downloads carry a `NetworkType.UNMETERED` WorkManager constraint and
 * defer until the device is on an unmetered (typically Wi-Fi) transport; `false`
 * ⇒ `NetworkType.CONNECTED`, so the user has opted in to spending mobile data.
 *
 * Before #1461 the toggle was persisted + cloud-synced but read by nothing —
 * every download call site hardcoded its `requireUnmetered` value. This is the
 * seam that makes the preference actually govern background (EAGER auto-poll)
 * downloads. Foreground "user tapped Listen / is actively listening" prefetch
 * paths deliberately stay `requireUnmetered = false` and do NOT consult this —
 * an explicit play intent overrides the data-saver default.
 */
interface DownloadNetworkConfig {
    /**
     * Snapshot of whether chapter downloads should require an unmetered
     * network. Defaults to `true` (Wi-Fi only) when unset — the
     * data-conscious default for the app's digital-divide audience.
     */
    suspend fun requireUnmeteredForDownloads(): Boolean
}
