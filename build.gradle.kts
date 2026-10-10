// Root build configuration.
//
// Plugin versions are declared once here with `apply false` and consumed by
// modules via `alias(libs.plugins...)` or `id("...")` without a version. This is
// the only place a plugin version may be written.
//
// Compatibility baseline (verified 2026-10-09 against official sources):
//
//   Gradle  8.13   - minimum required by AGP 8.13.x
//   AGP      8.13.2 - pins R8 8.13.19, which supports Kotlin 2.3
//   JDK      17     - AGP 8.13 minimum and default
//   Kotlin   2.3.21 - the KGP line AGP 8.13 was built against
//   KSP      2.3.12 - tracks the Kotlin 2.3.20 line; requires AGP >= 8.12
//   Compose  2.3.21 - must equal the Kotlin version (merged into Kotlin 2.0+)
//   Hilt     2.58   - LAST release supporting AGP 8; 2.59+ requires AGP 9
//   Room     2.8.5  - 2.6.x is NOT KSP2-safe (google/ksp#1896); 2.7+ is
//
// DEVIATIONS from the requested baseline, both forced by compatibility:
//
//  1. Kotlin 1.9.24 -> 2.3.21. KGP 1.9.20-1.9.25 is supported only up to
//     Gradle 8.1.1 / AGP 8.1.0, while AGP 8.13.2 requires Gradle 8.13. The two
//     requested baselines are mutually exclusive, so the Kotlin version moves.
//     Consequence: the Compose compiler is no longer configured through
//     `android.composeOptions.kotlinCompilerExtensionVersion` (that field only
//     serves Kotlin 1.9.x). Modules use the Compose Gradle plugin instead.
//
//  2. Hilt pinned to 2.58 rather than latest (2.60.1). Dagger 2.59 raised the
//     minimum AGP to 9.0.0 for the Hilt Gradle plugin; 2.58 is the final AGP-8
//     release. 2.60 also drops multidex and raises Hilt's own minSdk to 23,
//     which would conflict with this project's minSdk 21.

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    // Only com.google.dagger.hilt.android is published as a marker POM
    // (verified: hilt-android-gradle-plugin 2.58). There is no marker for the
    // bare "com.google.dagger.hilt" id that :ui:sip previously requested, so
    // declaring it here would break plugin resolution for the whole build.
    // :ui:sip uses no Hilt annotations, so it needs no Hilt plugin at all.
    id("com.google.dagger.hilt.android") version "2.58" apply false

    // The androidx.room Gradle plugin is deliberately NOT declared.
    //
    // It is optional: Room generates its code through KSP, and
    // room-compiler 2.7.2 ships its own KSP2 SymbolProcessorProvider
    // (androidx/room/RoomKspProcessor registered under
    // META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider).
    //
    // Declaring it also forced two problems: it pinned plugin 2.8.5 against the
    // 2.7.2 runtime actually used, and Room 2.8.5 requires minSdk 23 while this
    // project must stay on 21 (Room 2.7.2 declares minSdkVersion="21", verified
    // from its AAR manifest).
    //
    // Schema export is configured in data/core with the supported
    // `room.schemaLocation` KSP argument instead.
}
