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
    // epic/plugin-dx B3 — the voice-engine contract kit (testkit/voice)
    // references VoiceEnginePlugin/EngineKey. Plain `implementation`: the
    // only consumer of that kit class is core-playback's own test
    // sourceset, which has these types on its classpath already. (If this
    // edge ever forces a Gradle cycle, the spec's fallback is splitting
    // to :core-voice-testkit.)
    implementation(project(":core-playback"))
    api(libs.junit)
    api(libs.okhttp)
    api(libs.okhttp.mockwebserver)
    api(libs.kotlinx.coroutines.test)
}
