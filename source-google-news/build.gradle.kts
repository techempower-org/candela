plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.googlenews"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-data"))
    // #1295 — full-article text reuses Readability's fetch + boilerplate
    // stripping rather than reimplementing extraction. One-way dep
    // (:source-readability never references Google News) so no cycle.
    implementation(project(":source-readability"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // #1295 — parse the batchexecute JSON response (pure-JVM, so the
    // decoder's parse step is unit-testable without Robolectric).
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plugin-seam (#384) — KSP emits the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module for GoogleNewsSource. The
    // parallel @IntoMap binding in di/GoogleNewsModule.kt is kept
    // alongside (same pattern as :source-rss / :source-hackernews) so
    // the existing Map<String, FictionSource> repository routing keeps
    // resolving the source by its stable id.
    ksp(project(":core-plugin-ksp"))

    // The Google News feed is parsed with the JDK's javax.xml DOM
    // builder (available on Android + plain JVM), so the parser unit
    // tests run on a vanilla JUnit runner with no Robolectric — the
    // feed is plain RSS 2.0, simpler than :source-rss's multi-dialect
    // XmlPullParser path.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
