// Plugin-seam Phase 1 (#384) — KSP SymbolProcessor module.
//
// Pure Kotlin/JVM library (no Android dependency); KSP processors run
// inside the Kotlin compiler's JVM classpath, not in the consumer
// module's Android runtime. Consumer modules add this jar to their
// `ksp(...)` configuration, and the compiler invokes
// `SourcePluginProcessorProvider` (declared via
// `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`)
// to emit one `@Provides @IntoSet` factory per `@SourcePlugin`-annotated
// class.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)

    // Plain-JVM unit tests for the pure processor helpers (e.g. generated
    // module naming, #1506). Full KSP compile-testing is intentionally out
    // of scope — the collision logic is extracted into a pure function.
    testImplementation(libs.junit)
}
