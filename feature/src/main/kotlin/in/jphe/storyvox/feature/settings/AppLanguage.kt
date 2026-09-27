package `in`.jphe.storyvox.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The app-language choices offered in Settings → Appearance (#1585).
 *
 * [tag] is the BCP-47 tag handed to the per-app language API; the empty
 * tag means "follow the device" (an empty locale list clears the
 * override). Only languages with a real `values-<lang>` resource set are
 * listed — adding one means adding it here AND to
 * `app/src/main/res/xml/locales_config.xml`.
 */
enum class AppLanguage(val tag: String) {
    System(""),
    English("en"),
    Spanish("es"),
    ;

    companion object {
        /**
         * Map a stored/platform language-tag list ("es-US", "en,es", "") to
         * a choice. Matches on the primary language subtag of the first
         * entry; anything unsupported reads as [System] so the picker never
         * shows a selection the app can't honour.
         */
        fun fromTags(tags: String?): AppLanguage {
            val primary = tags.orEmpty()
                .substringBefore(',')
                .substringBefore('-')
                .substringBefore('_')
                .trim()
                .lowercase(Locale.ROOT)
            return entries.firstOrNull { it.tag.isNotEmpty() && it.tag == primary } ?: System
        }
    }
}

/**
 * Applies and reads the in-app language override (#1585).
 *
 * Two paths, because `MainActivity` is a `ComponentActivity`, not an
 * `AppCompatActivity`:
 *
 *  - **API 33+** — [AppCompatDelegate.setApplicationLocales] delegates to
 *    the platform `LocaleManager`, which persists the choice, keeps it in
 *    sync with Settings → System → Languages → App languages (listed there
 *    via `android:localeConfig`), and restarts the activity itself.
 *  - **API 26–32** — AppCompat's backport only re-applies stored locales
 *    from inside an `AppCompatActivity`, so it can't reach our activity.
 *    We persist the tag ourselves and `MainActivity.attachBaseContext`
 *    wraps its context through [wrap]; the switch recreates the activity.
 *    Scope on these releases is the activity UI — strings resolved from
 *    the Application context (e.g. notifications) follow the device.
 */
object AppLanguageController {

    private const val PREFS = "candela_app_language"
    private const val KEY_TAG = "tag"

    /** The currently effective choice. */
    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AppLanguage.fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())
        } else {
            AppLanguage.fromTags(storedTag(context))
        }

    /**
     * Switch the app to [language]. No-op when it's already selected, so a
     * tap on the active segment doesn't bounce the activity.
     */
    fun set(context: Context, language: AppLanguage) {
        if (current(context) == language) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Platform persists + recreates (locale isn't in configChanges).
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
        } else {
            prefs(context).edit { putString(KEY_TAG, language.tag) }
            context.findActivity()?.recreate()
        }
    }

    /**
     * For `attachBaseContext` on API < 33: overlay the stored locale onto
     * [base]. The override Configuration carries ONLY the locale, so the
     * activity still picks up rotation / density / night-mode changes.
     * Returns [base] untouched on 33+ or when no override is stored.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = storedTag(base)
        if (tag.isNullOrBlank()) return base
        val override = Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(override)
    }

    private fun storedTag(context: Context): String? =
        prefs(context).getString(KEY_TAG, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
