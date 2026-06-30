plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// ── #1405: the watch APK tracks the phone app's version ─────────────────────
// Library Nocturne is a wear-focused release, and a paired sideload must upgrade
// phone + watch in lockstep — a version skew across the Wearable Data Layer
// bridge is exactly the kind of mismatch that strands the watch on a stale
// protocol. So rather than carry a second version literal here (which silently
// drifted to 0.1.0 / versionCode 1 while :app advanced to 1.6.0 / 254), read
// :app's version straight from its build file — the single place the release
// pipeline bumps. A configuration-time file read is tracked as a build input,
// so this stays configuration-cache correct (cache is on; see gradle.properties)
// and a phone bump invalidates + refreshes this automatically.
val appBuildScript = rootProject.file("app/build.gradle.kts").readText()
val phoneVersionCode: Int =
    Regex("""\bversionCode\s*=\s*(\d+)""").find(appBuildScript)?.groupValues?.get(1)?.toInt()
        ?: error("wear/build.gradle.kts (#1405): could not read versionCode from app/build.gradle.kts")
val phoneVersionName: String =
    Regex("""\bversionName\s*=\s*"([^"]+)"""").find(appBuildScript)?.groupValues?.get(1)
        ?: error("wear/build.gradle.kts (#1405): could not read versionName from app/build.gradle.kts")

android {
    namespace = "in.jphe.storyvox.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "in.jphe.storyvox.wear"
        minSdk = 26
        targetSdk = 36   // #1406 — match :app's targetSdk (Android 16); compileSdk 37 ≥ 36.
        versionCode = phoneVersionCode   // #1405 — shared with :app (see top of file)
        versionName = phoneVersionName   // #1405 — shared with :app (see top of file)
    }

    signingConfigs {
        // Reuse :app's checked-in keystore instead of the per-machine
        // ~/.android/debug.keystore AGP defaults to, so the watch APK and
        // the phone APK share one signing certificate: the Wearable Data
        // Layer bridge only pairs phone <-> watch when both are signed
        // with the same key.
        getByName("debug") {
            storeFile = rootProject.file("app/storyvox-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            // #1407 — this is an INTENTIONAL sideload/companion config, NOT a
            // Play-Store-ready release build. Two deliberate choices:
            //   • minify OFF — the watch APK is sideloaded, not shrunk. R8 is
            //     deferred until a real Wear Play listing exists (it needs
            //     baseline testing first). proguardFiles is declared so turning
            //     R8 on later is a one-line flip, but it's inert while minify is off.
            //   • signed with :app's checked-in DEBUG keystore (see signingConfigs
            //     below) — the Wearable Data Layer bridge only pairs when phone +
            //     watch share a signing cert, and an unsigned release APK can't be
            //     adb-installed for sideload.
            // Before any Play Store Wear submission: switch to a real upload key
            // and evaluate enabling R8/minify. Until then this state is by design.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // An unsigned release APK can't be adb-installed; sign the
            // sideload build with the shared keystore above.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }


    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-playback"))
    implementation(project(":core-ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.coroutines)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    // Library Nocturne theme on Wear pulls EB Garamond + Inter from Google Fonts
    // via GMS, same provider configured in :core-ui font_certs.xml.
    implementation(libs.androidx.compose.ui.text.google.fonts)
    // Coil — loads PlaybackState.coverUri inside the circular scrubber. Same
    // version as the phone/tablet so a cover image already cached on the phone
    // doesn't need a fresh decode on the watch.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Wear Compose
    implementation(libs.bundles.wear.compose)
    implementation(libs.androidx.wear.tooling.preview)

    // Wear watch-face complications — data-source services + update requester
    // (the watch-face renders our Now Playing / Listening Stats complications).
    implementation(libs.androidx.wear.watchface.complications.datasource.ktx)
    // Wear Ongoing Activity — watch-face media chip while the phone plays
    implementation(libs.androidx.wear.ongoing)

    // Wear Tiles — glanceable "Now Playing" tile. Protolayout builds the layout;
    // the tiles lib hosts the TileService; concurrent-futures' CallbackToFutureAdapter
    // bridges the coroutine state read to the ListenableFuture the API returns.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.concurrent.futures)

    // Hilt (optional in v1; wired so Hypnos can use @AndroidEntryPoint)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Media3 session controller — Wear connects to phone's MediaSession
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    // Wear data layer — DataClient/MessageClient for phone bridge
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.lifecycle.runtime.compose)

    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    testImplementation(libs.junit)
}
