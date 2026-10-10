// SIP UI module - EXCLUDED from the default build, preserved for future work.
//
// WHY THIS IS EXCLUDED (verified 2026-10-10)
// ------------------------------------------------
// These two Compose screens have never compiled, for three independent reasons:
//   1. They import a PACKAGE, not a type:
//        import com.communicator.app
//      and then reference `app.SipRepository`, which is declared in
//      communication/sip/disabled-src/. A library module cannot reach that
//      source set at all.
//   2. They additionally lack real Compose imports, so `by remember` delegates
//      and layout modifiers such as fillMaxWidth do not resolve.
//   3. Nothing references these screens. No activity or composable mentions
//      SipCallScreen or SipAccountSetupScreen; MainActivity composes only
//      DialerScreen. They are unreachable, so excluding them cannot remove any
//      user-visible behaviour.
//
// They are also gated behind the PJSIP licensing decision, which is unresolved:
// PJSIP is GPL-2.0-or-later unless a commercial licence is obtained. That
// decision remains authoritative and is NOT revisited here.
//
// WHAT WOULD BE NEEDED TO ENABLE
// -----------------------------
//  * Resolve the PJSIP licensing question first (owner decision).
//  * Re-home a shared account/connection contract into a module both :ui:sip
//    and :app can depend on (:communication:sip), instead of referencing types
//    owned by the application module.
//  * Wire an actual navigation entry point, with tests, so the screens are
//    reachable rather than dead.
//
// Until then the sources stay on disk, unchanged, and are excluded rather than
// deleted: they are untracked, so deleting them would be unrecoverable.

val enableSipUi: Boolean =
    (findProperty("trojan.enableSipUi") as String?)?.toBoolean() ?: false

if (!enableSipUi) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        enabled = false
    }
    logger.lifecycle(
        "[sip-ui] :ui:sip is EXCLUDED: the screens are unreachable dead code and " +
            "reference types owned by :app. Enable with -Ptrojan.enableSipUi=true " +
            "only after the PJSIP licensing decision is resolved."
    )
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.communicator.ui.sip"
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
    implementation(project(":communication:sip"))
    implementation(project(":data:core"))
    implementation(project(":data:security"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.ui:ui:1.6.5")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.runtime:runtime:1.6.5")
    implementation("androidx.compose.foundation:foundation:1.6.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.libphonenumber:libphonenumber:8.13.38")
}
