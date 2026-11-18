plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xboxaudiobridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xboxaudiobridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:125.6422.07")
}
