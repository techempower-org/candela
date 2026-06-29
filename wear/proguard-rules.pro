# Library Nocturne (Wear OS companion) R8 / ProGuard rules.
#
# Intentionally empty: wear/build.gradle.kts keeps `isMinifyEnabled =
# false` on the release buildType, so R8 never runs and no keep rules are
# needed today. The file exists because the release block references it
# via `proguardFiles(...)` (mirroring :app and :core-sync, which both
# ship one). If minification is ever enabled here, add keep rules for the
# kotlinx-serialization @Serializable wire models under playback/ — the
# phone <-> watch Data Layer payloads are reflected over at runtime.
