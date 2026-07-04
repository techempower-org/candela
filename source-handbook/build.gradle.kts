plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.handbook"
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

    // @SourcePlugin -> KSP emits the SourcePluginDescriptor @IntoSet binding
    // AND the Map<String, FictionSource> @IntoMap binding (#1371). The
    // <Name>Reader seam is an @Inject class, so Hilt constructs it with no
    // hand-written di/ module.
    ksp(project(":core-plugin-ksp"))

    // Local sources do NOT use the HTTP contract kit — a plain fake-backed
    // unit test (see <Name>SourceTest) verifies the read path instead.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
