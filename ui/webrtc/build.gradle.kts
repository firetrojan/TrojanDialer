// WebRTC UI module - EXCLUDED from the default build, preserved for future work.
//
// WHY THIS IS EXCLUDED (verified 2026-10-10)
// ------------------------------------------------
// These two Compose screens have never compiled, for three independent reasons:
//   1. They import a PACKAGE, not a type:
//        import com.communicator.app
//      and then reference `app.WebRtcRepository`, which is declared in the
//      application module. A library module must not depend on :app, and this
//      module correctly does not.
//   2. `app.WebRtcConnectionState` is referenced here but declared NOWHERE in the
//      repository. It is not a renamed existing type either: the real concept
//      is WebRtcCallState.CallState in :communication:webrtc, which models call
//      progress, not signaling-connection state. There is no verified mapping
//      between them, so inventing one would fabricate a contract.
//   3. Nothing references these screens. No activity or composable mentions
//      WebRtcCallScreen or WebRtcAccountSetupScreen; MainActivity composes only
//      DialerScreen. They are unreachable, so excluding them removes no
//      user-visible behaviour.
//
// They additionally lack real Compose imports, so `by remember` delegates and
// fillMaxWidth do not resolve.
//
// WHAT WOULD BE NEEDED TO ENABLE
// -----------------------------
//  * Define a real connection-state contract in :communication:webrtc, owned by
//    the transport, and have WebRtcRepository expose it (it currently lives in
//    :app and must move down for a library module to consume it).
//  * Provide a signaling implementation. None exists: WebRtcTransportImpl
//    reports NoSignalingBackendException from connect(), so a "connected" badge
//    on this screen could never be truthful.
//  * Wire a navigation entry point with tests.
//
// Until then the sources stay on disk, unchanged. They are untracked, so
// deleting them would be unrecoverable; exclusion preserves them.
//
// NOTE ON SIZE: excluding this module's build does NOT remove the WebRTC AAR
// from the app, because :communication:webrtc remains a dependency of :app. The
// 46.9 MiB measurement therefore still applies. This gate is about
// compilability, not APK size.

val enableWebRtcUi: Boolean =
    (findProperty("trojan.enableWebRtcUi") as String?)?.toBoolean() ?: false

if (!enableWebRtcUi) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        enabled = false
    }
    logger.lifecycle(
        "[webrtc-ui] :ui:webrtc is EXCLUDED: the screens are unreachable dead " +
            "code and reference a type (WebRtcConnectionState) declared nowhere " +
            "plus a repository owned by :app. Enable with " +
            "-Ptrojan.enableWebRtcUi=true only after a real connection-state " +
            "contract and signaling exist."
    )
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.communicator.ui.webrtc"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":ui:core"))
    implementation(project(":communication:webrtc"))
    implementation(project(":data:core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.6.5")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.runtime:runtime:1.6.5")
    implementation("androidx.compose.foundation:foundation:1.6.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Needed by WebRtcCallScreen for EglBase / SurfaceViewRenderer.
    implementation("io.github.webrtc-sdk:android:150.7871.01")
}
