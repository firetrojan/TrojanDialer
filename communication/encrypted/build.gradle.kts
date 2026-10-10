plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.communicator.communication.encrypted"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    testOptions {
        unitTests {
            // android.jar stubs (Context, Log) may be referenced by unit tests;
            // the MLS tests never execute Android code, only Bouncy Castle.
            isReturnDefaultValues = true
        }
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
    api(project(":data:security"))
    api(project(":communication:core"))
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    // MLS for E2E encryption - Bouncy Castle MLS (RFC 9420)
    api("org.bouncycastle:bcmls-jdk18on:1.86")
    // Android Keystore for key storage
    api("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}
