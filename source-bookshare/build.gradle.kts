plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "in.jphe.storyvox.source.bookshare"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Issue #1002 — the DAISY DTBook parser uses `android.util.Xml`
            // (XmlPullParser), so unit tests need Android resources on the
            // classpath to resolve the SDK shim. Same posture as
            // `:source-palace` (#502) / `:source-rss` (#464).
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.android)
    // Issue #1002 — Bookshare API v2 client (JSON over OkHttp). Same stack
    // as :source-gutenberg's Gutendex client.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plugin-seam Phase 2 (#384) — emits the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module for BookshareSource. The legacy
    // @IntoMap binding lives in di/BookshareModule.kt for parity with the
    // other source modules until Phase 3 removes that pattern.
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric so `android.util.Xml` resolves for the DTBook XML parsing
    // path in unit tests — same dep used by `:source-palace` / `:source-rss`.
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
}
