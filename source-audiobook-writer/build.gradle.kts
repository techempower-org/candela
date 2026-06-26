plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Issue #1003 — "Make your own audiobook" M4B export.
//
// Distinct from the playback pipeline in :core-playback: this module holds
// only the *pure*, Android-framework-free pieces of the export so they stay
// testable in plain JUnit (no Robolectric, no MediaMuxer mocking):
//   - AudiobookModel  — the book/chapter inputs the encoder consumes
//   - Chapterizer     — splits a blob of pasted/imported text into chapters
//   - Mp4ChapterMarkers — turns per-chapter durations into the timecode list
//     the muxer needs, plus the QuickTime chapter-text track payload bytes
//
// The Android-specific encode + mux (MediaCodec AAC -> MediaMuxer .m4b) and the
// `ExportFictionToAudiobookUseCase` that orchestrates synthesis live in
// :core-playback, which already carries the VoxSherpa engines, EngineMutex,
// VoiceManager and WorkManager wiring. core-playback depends on this module;
// keeping this one a leaf (no project deps) avoids a dependency cycle and keeps
// the math unit-tested.
android {
    namespace = "in.jphe.storyvox.source.audiobook.writer"
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
    testImplementation(libs.junit)
}
