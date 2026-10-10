plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Required: this module declares Room entities and invokes
    // ksp("androidx.room:room-compiler"), which does not resolve without the
    // KSP Gradle plugin applied.
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.communicator.communication.mms"
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
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    api("androidx.room:room-ktx:2.7.2")
    api("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
}
