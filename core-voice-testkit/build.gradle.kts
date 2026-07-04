plugins {
    // Kotlin compilation is provided by AGP's built-in Kotlin support
    // (`android.builtInKotlin=true` in gradle.properties), so — like the
    // sibling :core-source-testkit — this library applies only the Android
    // library plugin; there is no `kotlin.android` alias in the version catalog.
    alias(libs.plugins.android.library)
}

android {
    namespace = "in.jphe.storyvox.testkit.voice"
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
    // #1504 — the voice-engine contract kit (abstract
    // VoiceEnginePluginContractTest) split out of :core-source-testkit so
    // source modules stop dragging :core-playback (the 6300-line EnginePlayer
    // hot file) into their test compile. The kit exposes VoiceEnginePlugin /
    // EngineKey / ModelSpec in its protected signatures, so :core-playback is
    // `api` — its only consumer is :core-playback's own unit tests, which
    // subclass the kit. Everything else the abstract class needs (JUnit) is
    // likewise `api` so subclasses compile without re-declaring it.
    api(project(":core-playback"))
    api(libs.junit)
}
