plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.floattime.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.floattime.app"
        minSdk = 30                    // API 30 = Android 11 (Live Island minimum)
        targetSdk = 36                 // API 36 = Android 16 (latest)
        versionCode = 32
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 启用 multidex（增加 MIUI Extensions 后方法数增加）
        multiDexEnabled = true
    }

    buildTypes {
        release {
            // ✅ R8 压缩，减小 APK 体积
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

    androidResources {
        excludes += listOf(
            "**/*.gif",
            "**/raw/**",
            "drawable-nodpi/**"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // ✅ OkHttp（替代 HttpURLConnection）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ✅ 新增：AndroidX Window Extensions（Live Island 核心 API）
    implementation("androidx.window:window:1.3.0")

    // ✅ 新增：AndroidX Window Core（WindowAreaComponent 等）
    implementation("androidx.window:window-core:1.3.0")

    // ✅ 新增：MIUI Extensions（小米 HyperOS 定制组件，Live Island 必须）
    // 仅支持 xiaomi / redmi / poco 设备，会在运行时自动 fallback
    compileOnly("androidx.window:window-extensions:1.0.0-alpha04")

    // ✅ AndroidX Startup（InitializationProvider 启动优化）
    implementation("androidx.startup:startup-runtime:1.1.1")

    // ✅ Multidex
    implementation("androidx.multidex:multidex:2.0.1")
}
