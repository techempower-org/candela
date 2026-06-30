plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "in.jphe.storyvox.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "in.jphe.storyvox.wear"
        minSdk = 26
        targetSdk = 34   // Wear OS targets sdk 34 in 2026; bump alongside Wear platform support.
        versionCode = 1
        versionName = "0.1.0"
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
