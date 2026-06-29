package `in`.jphe.storyvox.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

/**
 * Issue #1234 — Android dispatch for a shared quote. The string itself is
 * built by the pure [QuoteShareFormatter]; this file does only the
 * Context-coupled half (system share sheet + clipboard + toast), mirroring the
 * intent/clipboard pattern already established in
 * `feature/techempower/TechEmpowerIntents.kt`. Not unit-tested (no Robolectric
 * in this project — see CLAUDE.md); the testable logic is the formatter.
 */

/** Clipboard label shown by the OS clip-preview UI on Android 13+. */
private const val CLIP_LABEL = "Candela quote"

/**
 * Open the system share sheet (`Intent.ACTION_SEND`, `text/plain`) for a
 * pre-formatted [text]. Wrapped in `Intent.createChooser` so the user always
 * gets the picker (rather than a remembered default), and tagged
 * `FLAG_ACTIVITY_NEW_TASK` so it launches cleanly from a Composable's
 * non-Activity context — the same guard the TechEmpower launchers use.
 */
internal fun shareQuoteText(context: Context, text: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/**
 * Copy [text] to the clipboard and confirm with a [toastMessage].
 *
 * Android 13+ (API 33) shows its own system clip-preview chip on copy, so we
 * suppress the redundant toast there to avoid a double confirmation — the
 * pattern Google recommends for `setPrimaryClip`. On older releases the toast
 * is the only feedback, so it always fires.
 */
internal fun copyQuoteToClipboard(context: Context, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }
}
