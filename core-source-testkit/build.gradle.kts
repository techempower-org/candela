plugins {
    // Kotlin compilation is provided by AGP's built-in Kotlin support
    // (`android.builtInKotlin=true` in gradle.properties), so — like every
    // sibling source module — this library applies only the Android library
    // plugin; there is no `kotlin.android` alias in the version catalog.
    alias(libs.plugins.android.library)
}

android {
    namespace = "in.jphe.storyvox.testkit.source"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 + `android.builtInKotlin=true` derives the Kotlin jvmTarget
    // from compileOptions.targetCompatibility, so no explicit
    // `kotlin { compilerOptions { jvmTarget } }` block is needed here.
}

dependencies {
    // The abstract contract test lives in this module's MAIN sourceset
    // (test-fixtures style) so consumers pull it with a single
    // `testImplementation(project(":core-source-testkit"))`. Everything the
    // abstract class exposes in a public/protected signature — FictionSource,
    // OkHttpClient, MockWebServer, JUnit annotations — is therefore `api`, so
    // subclasses in other modules compile without re-declaring these deps.
    api(project(":core-data"))
    // #1504 — the voice-engine contract kit moved to :core-voice-testkit, so
    // this source kit no longer depends on :core-playback. That's the win:
    // source modules adopting this kit no longer drag the 6300-line
    // EnginePlayer hot file into their test compile.
    api(libs.junit)
    api(libs.okhttp)
    api(libs.okhttp.mockwebserver)
    api(libs.kotlinx.coroutines.test)
}
