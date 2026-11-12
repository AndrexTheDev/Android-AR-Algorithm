plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.arena.arkernel.diagnostics"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.arena.arkernel.diagnostics"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "2.9-feed"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":android:arkernel"))
}
