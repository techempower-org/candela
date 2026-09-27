package `in`.jphe.storyvox.auth.googledrive

import `in`.jphe.storyvox.BuildConfig
import java.net.URLEncoder

/**
 * Issue #1677 — the Google Picker leg of the Drive integration.
 *
 * Under the non-sensitive `drive.file` scope Candela can only read files the
 * user explicitly picks. Google's Picker is a JavaScript API that must run on
 * an HTTPS origin registered with the API key, so Candela opens a tiny static
 * page — `docs/drive-picker.html`, served by GitHub Pages at [PICKER_PAGE_URL]
 * — in a Chrome Custom Tab, hands it the session in the URL **fragment**
 * (never sent to any server), and the page returns to [RETURN_URI] when the
 * user is done. Picking a file there is what grants Candela access to it.
 *
 * Needs two more public build values beside the OAuth client id, both with
 * empty defaults (the "Choose from Drive" button hides without them):
 *  - `GOOGLE_PICKER_API_KEY` — a browser API key restricted to the Picker API
 *    and the Pages origin. Picker keys are public by design (every web page
 *    using the Picker ships one); the referrer restriction is the control.
 *  - `GOOGLE_CLOUD_PROJECT_NUMBER` — the Picker `appId`. It must be the number
 *    of the project that owns the OAuth client, or picks won't grant access.
 */
object GoogleDrivePickerConfig {

    /** Static picker page (GitHub Pages, `docs/drive-picker.html`). */
    const val PICKER_PAGE_URL = "https://candela.techempower.org/drive-picker.html"

    /** Where the picker page sends the user back. Covered by the existing
     *  `candela://oauth/googledrive` manifest intent-filter. */
    const val RETURN_URI = "candela://oauth/googledrive/picked"

    /** The MIME types the Picker offers — every type `:source-google-drive`
     *  can narrate (kept in step with `GoogleDriveSource.READABLE_MIME_TYPES`). */
    val PICKABLE_MIME_TYPES: List<String> = listOf(
        "application/vnd.google-apps.document",
        "application/pdf",
        "application/epub+zip",
        "text/plain",
        "text/markdown",
    )

    val apiKey: String get() = BuildConfig.GOOGLE_PICKER_API_KEY
    val appId: String get() = BuildConfig.GOOGLE_CLOUD_PROJECT_NUMBER

    /** True when this build can run the whole connect + pick flow. */
    val isAvailable: Boolean
        get() = GoogleDriveOAuthConfig.isAvailable && apiKey.isNotBlank() && appId.isNotBlank()

    /** The picker URL for [accessToken], or null when unavailable. */
    fun pickerUrl(accessToken: String, languageTag: String): String? {
        if (!isAvailable || accessToken.isBlank()) return null
        return buildPickerUrl(
            pageUrl = PICKER_PAGE_URL,
            accessToken = accessToken,
            apiKey = apiKey,
            appId = appId,
            returnUri = RETURN_URI,
            mimeTypes = PICKABLE_MIME_TYPES,
            languageTag = languageTag,
        )
    }
}

/**
 * #1677 — pure builder (unit-tested). Every value rides the fragment, so the
 * access token never reaches the Pages server or its logs.
 */
internal fun buildPickerUrl(
    pageUrl: String,
    accessToken: String,
    apiKey: String,
    appId: String,
    returnUri: String,
    mimeTypes: List<String>,
    languageTag: String,
): String {
    val params = listOf(
        "token" to accessToken,
        "key" to apiKey,
        "app" to appId,
        "return" to returnUri,
        "mimes" to mimeTypes.joinToString(","),
        "hl" to languageTag,
    )
    return pageUrl + "#" + params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
}
