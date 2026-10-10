plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.communicator.communication.sip"
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
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    // PJSIP for real SIP implementation.
    //
    // EXTERNAL_DEPENDENCY_MISSING - this coordinate has never been published.
    // com.cocolove2.library:pjsip:1.0.1 returns 404 on both Maven Central and
    // Google Maven, so this module cannot resolve a single dependency and can
    // never compile. It is excluded from the CI compile gate for that reason,
    // not because the module is finished.
    //
    // :app does not depend on :communication:sip or :ui:sip and declares no SIP
    // capability, so the shipped app is unaffected. Resolving this means
    // vendoring a PJSIP build whose licence and provenance are acceptable, and
    // is deliberately deferred rather than worked around.
    api("com.cocolove2.library:pjsip:1.0.1")
}
