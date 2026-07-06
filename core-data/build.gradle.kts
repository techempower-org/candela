plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        // Room's MigrationTestHelper needs the schema JSONs visible
        // on the test classpath so it can reconstruct the prior-version
        // databases. Without this entry it fails with
        // "Cannot find the schema file in the assets folder."
        getByName("test") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            // Robolectric needs Android resources on the unit-test classpath
            // so MigrationTestHelper can spin up a real SQLiteOpenHelper.
            isIncludeAndroidResources = true
            // #1564 — FictionSourceIdResolver logs LOUD (android.util.Log.w)
            // when it hits a colon-less non-numeric id. Its plain-JVM test
            // exercises that path, so let the android stubs return defaults
            // instead of throwing "not mocked". (No effect on Robolectric
            // tests, which supply real android impls.)
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

dependencies {
    // Kotlin / Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // #1628 — shared HTML→plaintext (htmlToPlainText) for chapter bodies.
    implementation(libs.jsoup)

    // Android KTX (provides SharedPreferences.edit { ... })
    implementation(libs.androidx.core.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    // EncryptedSharedPreferences
    implementation(libs.androidx.security.crypto)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
}
