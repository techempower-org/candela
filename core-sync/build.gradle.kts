import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * InstantDB app id read from `local.properties` at configure time. Falls
 * back to the literal `"PLACEHOLDER"` sentinel so a clean checkout (CI,
 * new contributor, secrets-rotated dev machine) still builds — the sync
 * layer's DI graph routes to [DisabledBackend] when the sentinel is seen.
 *
 * Pattern mirrors how android-sdk.dir is sourced. We deliberately do NOT
 * accept the value via `-P` on the command line — that pattern leaks
 * secrets into shell history and CI logs. local.properties is gitignored
 * and lives only on JP's machine + the self-hosted runner.
 */
val instantAppId: String = run {
    val propsFile = rootProject.file("local.properties")
    if (!propsFile.exists()) return@run "PLACEHOLDER"
    val props = Properties().apply { propsFile.inputStream().use(::load) }
    (props.getProperty("INSTANTDB_APP_ID") ?: "PLACEHOLDER").trim().ifBlank { "PLACEHOLDER" }
}

/**
 * Optional Instant API base (`INSTANTDB_API_URI` in local.properties), so a
 * build can talk to a self-hosted Instant instead of Instant Cloud (which
 * shuts down 2027-08-31). Blank → Instant Cloud. Must be https.
 */
val instantApiUri: String = run {
    val propsFile = rootProject.file("local.properties")
    if (!propsFile.exists()) return@run ""
    val props = Properties().apply { propsFile.inputStream().use(::load) }
    val v = (props.getProperty("INSTANTDB_API_URI") ?: "").trim()
    if (v.isNotEmpty() && !v.startsWith("https://")) {
        throw GradleException("INSTANTDB_API_URI must be an https:// URL")
    }
    v
}

android {
    namespace = "in.jphe.storyvox.sync"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "INSTANTDB_APP_ID", "\"$instantAppId\"")
        buildConfigField("String", "INSTANTDB_API_URI", "\"$instantApiUri\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    // Issue #778 — `LwwBlobSyncer.reconcile` was instrumented with
    // `android.util.Log` calls in commit 7b89fe88 (sync FK guard), which
    // throws `RuntimeException: Method d in android.util.Log not mocked`
    // under the JVM unit-test runner. Returning default values makes
    // Log.d a no-op in tests — same shape every other Android module
    // uses for its unit-test source set. This unblocks both the new
    // `PronunciationDictSyncerTest` and the pre-existing
    // `SettingsSyncerTest` that started failing on the same commit.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.okhttp)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
}

// InstantPermsTest reads instant.perms.json directly; declare it so a rules
// edit re-runs the test instead of being skipped as up-to-date.
tasks.withType<Test>().configureEach {
    inputs.file("instant.perms.json").withPropertyName("instantPerms")
}
