plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Optional WebRTC transport. Off by default; see the dependency block below
// for the reachability evidence and the measured size cost.
val enableWebRtc: Boolean =
    (findProperty("trojan.enableWebRtc") as String?)?.toBoolean() ?: false

android {
    namespace = "com.communicator.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.communicator.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{DEPENDENCIES,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
        }
    }
}

// SIP (:communication:sip, :ui:sip) is deliberately absent: its PJSIP engine
// has no resolvable, legally acceptable dependency yet, and app.SipRepository is
// the only app-side consumer. Nothing in the running app declares a SIP
// capability. See communication/sip/build.gradle.kts.
dependencies {
    implementation(project(":ui:core"))
    implementation(project(":ui:dialer"))
    implementation(project(":ui:incoming"))
    implementation(project(":ui:settings"))
    // :ui:webrtc is deliberately absent: its two screens are unreachable dead
    // code that reference a type declared nowhere (WebRtcConnectionState) and a
    // repository owned by :app, so the module has never compiled. It is gated
    // behind -Ptrojan.enableWebRtcUi=true and its sources are preserved.
    implementation(project(":contacts:core"))
    implementation(project(":contacts:ui"))
    implementation(project(":communication:core"))
    implementation(project(":communication:carrier"))
    implementation(project(":communication:ims"))
    implementation(project(":communication:sms"))
    implementation(project(":communication:rcs"))
    // :communication:webrtc is OPTIONAL and OFF by default.
    //
    // Measured cost when enabled: the io.github.webrtc-sdk:android:150.7871.01
    // AAR is 49,147,033 bytes (46.9 MiB) carrying four ABIs, with the arm64-v8a
    // native library alone at 12,287,312 bytes. That is roughly 6-9x the
    // project's stated preference of a 5-8 MB increase for an optional
    // subsystem.
    //
    // It is therefore excluded from the default build, which is honest here
    // because nothing in the reachable application graph uses it. Verified:
    //   * WebRtcRepository is never constructed anywhere in the repository;
    //   * the only construction of WebRtcTransportImpl happens INSIDE
    //     WebRtcRepository, so it is unreachable too;
    //   * WebRtcMlsAdapter is never constructed;
    //   * no @Inject/@Provides/@Binds supplies any of them;
    //   * the AndroidManifest declares no WebRTC component;
    //   * no XML or navigation resource references WebRTC;
    //   * the two files that DO reference WebRtcRepository are the gated-out
    //     ui/webrtc screens.
    //
    // Build the WebRTC-enabled variant with:
    //   sh gradlew -Ptrojan.enableWebRtc=true :app:assembleDebug
    //
    // Signaling remains EXTERNAL_BACKEND_REQUIRED in both variants:
    // WebRtcTransportImpl.connect() throws NoSignalingBackendException, so
    // enabling this dependency produces a transport that still cannot negotiate.
    implementation(project(":communication:encrypted"))
    implementation(project(":communication:mesh"))
    implementation(project(":communication:recording"))
    implementation(project(":communication:spam"))
    implementation(project(":communication:history"))
    implementation(project(":communication:diagnostics"))
    implementation(project(":communication:mms"))

    // Added back only for the WebRTC-enabled variant.
    if (enableWebRtc) {
        implementation(project(":communication:webrtc"))
        logger.lifecycle("[webrtc] :communication:webrtc ENABLED via -Ptrojan.enableWebRtc=true")
    } else {
        logger.lifecycle(
            "[webrtc] :communication:webrtc EXCLUDED from the default app. " +
                "It contributed a 46.9 MiB AAR and no reachable code path uses it. " +
                "Enable with -Ptrojan.enableWebRtc=true."
        )
    }
    implementation(project(":data:core"))
    implementation(project(":data:security"))
    implementation(project(":core:common"))
    implementation(project(":core:extensions"))
    implementation(project(":core:permissions"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.2.0-alpha04")
    implementation("androidx.compose.ui:ui:1.6.5")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.runtime:runtime:1.6.5")
    implementation("androidx.compose.foundation:foundation:1.6.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-android-compiler:2.58")
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Accompanist for system UI controllers

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.0.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.0.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.5.1")
    testImplementation("app.cash.turbine:turbine:1.0.0")
}
