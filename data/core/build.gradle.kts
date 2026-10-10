plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Export the generated schema JSON so the schema history is reviewable and
// future migrations can be written against real versions. CommunicatorDatabase
// sets exportSchema = true, which makes this export mandatory.
//
// `arg(...)` is only available in the top-level ksp extension block, not inside
// dependencies { }; configuring it there fails script compilation.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

android {
    namespace = "com.communicator.data.core"
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
    api(project(":core:extensions"))
    api(project(":core:permissions"))
    
    api("androidx.room:room-ktx:2.7.2")
    api("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    
    // Every DAO in this module returns Flow<...>, so the coroutines core types
    // are part of this module's own API surface. It currently inherits them
    // transitively through :core:common; declaring them here makes the
    // requirement explicit rather than incidental.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
}
