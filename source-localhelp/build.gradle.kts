import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Issue #1465 — 211 National Data Platform subscription key, read from the
 * gitignored `local.properties` at configure time (CI materializes it from
 * the `TWO11_API_KEY` Actions secret). Empty by default so a clean checkout
 * and CI build green WITHOUT a key; the source then reports "not configured"
 * instead of issuing requests, and ships toggled OFF (defaultEnabled = false).
 * Same posture as :app's OAuth client ids —
 * deliberately NOT via `-P` (leaks into shell history / CI logs).
 *
 *   TWO11_API_KEY=<Api-Key from apiportal.211.org>
 *
 * NOTE: a Trial-product key is licensed for development/testing only; any
 * production use needs permission from each participating 211 (see
 * docs/localhelp-211-setup.md).
 */
val two11ApiKey: String = run {
    val propsFile = rootProject.file("local.properties")
    if (!propsFile.exists()) return@run ""
    val props = Properties().apply { propsFile.inputStream().use(::load) }
    (props.getProperty("TWO11_API_KEY") ?: "").trim()
}

android {
    namespace = "in.jphe.storyvox.source.localhelp"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "TWO11_API_KEY", "\"$two11ApiKey\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // @SourcePlugin -> KSP emits the SourcePluginDescriptor @IntoSet binding
    // AND the Map<String, FictionSource> @IntoMap binding (#1371). The
    // scaffold also generates di/<Name>Module.kt, which provides this source's
    // dedicated OkHttpClient + Api — that one you keep (don't delete it; #1522).
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core-source-testkit"))
}
