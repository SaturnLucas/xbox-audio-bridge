plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.xboxaudiobridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xboxaudiobridge"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Chave fixa (vem dos secrets do GitHub) para que as atualizações pelo app sejam aceitas pelo Android
    signingConfigs {
        val ks = System.getenv("KS_PATH")
        if (ks != null) {
            create("ci") {
                storeFile = file(ks)
                storePassword = System.getenv("KS_PASS")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASS")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("ci")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    implementation("androidx.core:core:1.13.1")
}
