plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.communicator.communication.diagnostics"
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
}

dependencies {
    api(project(":core:common"))
    api(project(":data:core"))
    api(project(":communication:core"))
    api(project(":communication:carrier"))
    api(project(":communication:ims"))
    api(project(":communication:sms"))
    api(project(":communication:rcs"))
    // :communication:webrtc is optional and follows the app's flag.
    //
    // This module declares no WebRTC type in its own source, so the dependency
    // existed only to re-export it: :app -> :diagnostics -> :communication:webrtc
    // meant the 46.9 MiB AAR still reached the default APK even after :app
    // stopped depending on it directly.
    if ((findProperty("trojan.enableWebRtc") as String?)?.toBoolean() == true) {
        api(project(":communication:webrtc"))
    }
    api(project(":communication:encrypted"))
    api(project(":communication:mesh"))
    api(project(":communication:recording"))
    api(project(":communication:spam"))
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
