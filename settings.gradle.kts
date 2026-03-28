pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Shizuku（需要 JitPack）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "FloatTime"
include(":app")
