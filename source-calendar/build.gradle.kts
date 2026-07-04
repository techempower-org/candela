plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.calendar"
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
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // #1495 — @SourcePlugin -> KSP emits BOTH the SourcePluginDescriptor
    // @IntoSet binding AND the Map<String, FictionSource> @IntoMap binding
    // (#1371). No hand DI module for the source itself. This is a
    // local-provider source (Android CalendarContract, zero network), so it
    // ships no okhttp/serialization deps and does NOT use the HTTP contract
    // kit — same posture as :source-ocr / :source-epub.
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
