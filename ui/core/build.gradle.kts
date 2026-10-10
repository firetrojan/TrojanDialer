plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.communicator.ui.core"
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
    api(project(":core:common"))
    api(project(":data:core"))
    api("androidx.core:core-ktx:1.13.1")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    api("androidx.compose.ui:ui:1.6.5")
    api("androidx.compose.material3:material3:1.3.0")
    api("androidx.compose.runtime:runtime:1.6.5")
    api("androidx.activity:activity-compose:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    api("io.coil-kt:coil-compose:2.6.0")
}
