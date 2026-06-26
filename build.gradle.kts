plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Issue #409 — `com.android.test` is the AGP variant used by
    // `:baselineprofile`. Declared here so subproject `plugins { id(...) }`
    // blocks can pick up the same AGP version as :app / library modules
    // (the plugin resolution mechanism requires a root-level marker
    // when the version is omitted at the leaf).
    alias(libs.plugins.android.test) apply false
    // Issue #409 — `androidx.baselineprofile` Gradle plugin. Applied
    // to both :app (consumer side) and :baselineprofile (producer side).
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // Issue #1142 — mikepenz AboutLibraries `.android` plugin. Declared at
    // the root so :app can apply it without re-stating the version; it
    // auto-generates OSS license metadata during :app's Android build.
    alias(libs.plugins.aboutlibraries.android) apply false
}
