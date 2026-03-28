plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.floattime.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.floattime.app"
        minSdk = 27
        targetSdk = 34
        versionCode = 31
        versionName = "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // ✅ 优化: 启用 R8 压缩，减小 APK 体积 10-20%
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

    // ✅ 优化: 移除未使用的资源文件
    androidResources {
        excludes += listOf(
            "*.xml",                       // 排除所有 xml（保留 res/xml/config.xml 如有需要）
            "!res/xml/config.xml",         // 但保留 config.xml
            "**/*.gif",                    // 排除 GIF
            "**/*.webp",                   // 排除 WebP（如果启用了 AVIF 可进一步排除）
            "**/raw/**",                  // 排除 raw 资源
            "drawable-nodpi/**"           // 排除 nodpi 资源（通常用于高清壁纸）
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // ✅ 新增: OkHttp（替代 HttpURLConnection）
    // Gradle 会自动处理 transitive dependencies (okio)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
