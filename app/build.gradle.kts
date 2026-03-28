plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.floattime.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.floattime.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 32
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // AndroidX Window Extensions（Live Island 核心 API）
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.window:window-core:1.3.0")

    // AndroidX Startup
    implementation("androidx.startup:startup-runtime:1.1.1")

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")
}
