plugins { id("com.android.library") }

android {
    namespace = "ai.arena.arkernel"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        externalNativeBuild { cmake { cppFlags += "-std=c++17 -Wall -Wextra -Wpedantic" } }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild { cmake { path = file("CMakeLists.txt"); version = "3.22.1" } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
