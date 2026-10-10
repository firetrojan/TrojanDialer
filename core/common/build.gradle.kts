plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.communicator.core.common"
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
        compose = false
    }
}

dependencies {
    api("androidx.core:core-ktx:1.13.1")
    api("androidx.appcompat:appcompat:1.7.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // NOTE: this module previously declared api(project(":data:core")), while
    // :data:core declares api(project(":core:common")). That is a dependency
    // cycle, and Gradle rejects it with
    //   "Circular dependency between the following tasks:
    //    :core:common:bundleLibCompileToJarDebug
    //    +--- :core:common:compileDebugJavaWithJavac"
    // which fails :data:core:compileDebugKotlin before any Kotlin is compiled.
    //
    // The edge is removed because it is unused: :core:common contains no Kotlin
    // or Java sources, so it cannot reference anything in :data:core. The
    // library dependencies above are all it actually needs.
}
