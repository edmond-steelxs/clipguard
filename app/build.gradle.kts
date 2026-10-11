plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.edmond.clipguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.edmond.clipguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.7"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
