plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.communicator.communication.webrtc"
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
    // WebRtcMlsAdapter drives the Stage 5D durable queue through
    // MlsGroupManager (processOutboundQueue / processInbound), so this module
    // needs the encrypted module on its compile classpath. Without it
    // :communication:webrtc:compileDebugKotlin failed with
    // "Unresolved reference 'MlsGroupManager'".
    // No cycle: :communication:encrypted does not depend on :communication:webrtc.
    api(project(":communication:encrypted"))
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    // WebRTC implementation
    api("io.github.webrtc-sdk:android:150.7871.01")
}
