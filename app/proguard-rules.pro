# ==============================================================
# FloatTime ProGuard Rules
# ==============================================================

# ✅ OkHttp rules（必须保持）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ✅ Kotlin 运行时保留
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ✅ JSONObject 保留（淘宝/美团时间解析）
-keep public class org.json.** {
    public protected *;
}
-keepclassmembers class org.json.** {
    public protected *;
}

# ✅ Android 系统组件（Manifest 声明的 Service/Receiver/Activity）
-keep class com.floattime.app.FloatTimeService { *; }
-keep class com.floattime.app.ClockTileService { *; }
-keep class com.floattime.app.BootReceiver { *; }
-keep class com.floattime.app.MainActivity { *; }
-keep class com.floattime.app.IslandManager { *; }

# ✅ IslandManager 子类（LiveIslandHandler/HyperIslandHandler/NotificationHandler）
-keep class com.floattime.app.IslandManager$* { *; }

# ✅ AndroidX Window Extensions（Live Island API）
-keep class androidx.window.** { *; }
-keep class androidx.activity.** { *; }

# ✅ AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# ✅ 通知兼容库
-keep class androidx.core.app.NotificationCompat { *; }

# ✅ R8 优化提示
-allowaccessmodification
-dontskipnonpubliclibraryclasses
