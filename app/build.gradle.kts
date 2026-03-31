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
        versionCode = 33
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        multiDexEnabled = true
    }

    buildFeatures {
        viewBinding = true
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

    lint {
        abortOnError = false
        warningsAsErrors = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Material Design（BottomSheetBehavior）
    implementation("com.google.android.material:material:1.12.0")

    // ConstraintLayout + CoordinatorLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // AndroidX Window Extensions（Live Island 核心 API）
    implementation("androidx.window:window:1.3.0")

    // AndroidX Startup
    implementation("androidx.startup:startup-runtime:1.1.1")

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Shizuku API — 通过 Shizuku 获取特权操作能力
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5@aar")

    // HyperNotification — 小米焦点通知 V3 DSL
    implementation("com.xzakota.hyper.notification:focus-api:1.4")

    // window-extensions: 运行时由 MIUI/HyperOS 系统私有实现提供，
    // 不在公共仓库，Kotlin 代码通过反射调用，不需 compileOnly 依赖

    // Unit Testing
    testImplementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
}