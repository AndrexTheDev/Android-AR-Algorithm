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
        versionCode = 6
        versionName = "2.4-campaign"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":android:arkernel"))
}
