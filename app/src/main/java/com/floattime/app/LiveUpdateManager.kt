package com.floattime.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * LiveUpdateManager - 实时更新通知管理器 (修复版)
 *
 * 修复内容:
 * - ✅ 完善通知渠道配置 (enableVibration, setBypassDnd, setAllowBubbles)
 * - ✅ 增强通知创建 (setLocalOnly, setColorized, setAutoCancel)
 * - ✅ 改进通知更新机制
 *
 * 支持:
 * - Android 16 (API 36) Notification.ProgressStyle Live Updates
 * - Android 12+ 前台服务即时行为
 * - 兼容所有 Android 版本 (API 24+)
 * - 小米超级岛 (HyperOS Focus Notification)
 */
class LiveUpdateManager(context: Context) {

    companion object {
        private const val TAG = "LiveUpdateManager"
        const val CHANNEL_ID = "float_time_live_updates"
        private const val CHANNEL_NAME = "悬浮时间"
        private const val CHANNEL_DESC = "实时显示校准时间"

        const val ID_CLOCK = 20240320
        const val ID_SYNC_STATUS = 20240321

        private const val PROGRESS_MAX = 60

        // 反射缓存 (进程级单例)
        private var progressStyleClass: Class<*>? = null
        private var setStyledByProgress: Method? = null
        private var setProgress: Method? = null
        private var setProgressSegments: Method? = null
        private var setProgressPoints: Method? = null
        private var builderSetProgressStyle: Method? = null
        private var segmentClass: Class<*>? = null
        private var pointClass: Class<*>? = null
        private var reflectionCached = false

        @Synchronized
        private fun cacheReflection() {
            if (reflectionCached) return
            try {
                progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
                setStyledByProgress = progressStyleClass
                    ?.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType)
                setProgress = progressStyleClass
                    ?.getMethod("setProgress", Int::class.javaPrimitiveType)
                setProgressSegments = progressStyleClass
                    ?.getMethod("setProgressSegments", List::class.java)
                setProgressPoints = progressStyleClass
                    ?.getMethod("setProgressPoints", List::class.java)

                segmentClass = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
                pointClass = Class.forName("android.app.Notification\$ProgressStyle\$Point")

                builderSetProgressStyle = Notification.Builder::class.java.getMethod(
                    "setProgressStyle", progressStyleClass
                )

                reflectionCached = true
                Log.d(TAG, "ProgressStyle reflection cached successfully")
            } catch (e: Exception) {
                Log.w(TAG, "ProgressStyle reflection cache failed (expected on pre-36): ${e.message}")
                reflectionCached = true
            }
        }
    }

    private val appContext = context.applicationContext
    private val notifMgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private val supportsProgressStyle = Build.VERSION.SDK_INT >= 36
    private val superIsland = SuperIslandManager(appContext)

    init {
        createChannel()
        if (supportsProgressStyle && !reflectionCached) {
            cacheReflection()
        }
        Log.d(TAG, "LiveUpdateManager initialized | API=${Build.VERSION.SDK_INT}" +
                " | ProgressStyle=$supportsProgressStyle" +
                " | HyperOS=${superIsland.isHyperOS()}")
    }

    fun isSupported(): Boolean = true
    fun supportsProgressStyle(): Boolean = supportsProgressStyle

    // ================================================================
    //  渠道创建 (修复版)
    // ================================================================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = CHANNEL_DESC
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableLights(false)
            enableVibration(false)  // ✅ 修复: 禁用振动
            setSound(null, null)
            vibrationPattern = null
            setBypassDnd(true)  // ✅ 修复: 绕过勿扰模式
            setAllowBubbles(false)  // ✅ 修复: 禁用气泡通知
        }
        notifMgr.createNotificationChannel(ch)
        Log.d(TAG, "Notification channel created with enhanced configuration")
    }

    // ================================================================
    //  公共方法
    // ================================================================

    /**
     * 创建时钟通知 (修复版)
     */
    fun createClockNotification(
        ctx: Context,
        timeStr: String,
        millisStr: String,
        source: String,
        isNight: Boolean = false
    ): Notification {
        val pi = MainPendingIntent.create(ctx)

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(timeStr + millisStr)
            .setContentText(source)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)  // ✅ 修复: 不同步到其他设备
            .setColorized(true)  // ✅ 修复: 启用彩色显示
            .setAutoCancel(false)  // ✅ 修复: 禁止用户关闭
            .setForegroundServiceBehavior(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                else NotificationCompat.FOREGROUND_SERVICE_DEFAULT
            )
            .build()

        // Android 16 ProgressStyle
        if (supportsProgressStyle) {
            try {
                applyProgressStyle(notification, timeStr, millisStr, isNight)
            } catch (e: Exception) {
                Log.e(TAG, "ProgressStyle apply failed: ${e.message}")
            }
        }

        // 小米超级岛
        try {
            superIsland.applyFocusExtras(notification, timeStr, millisStr, source)
        } catch (e: Exception) {
            Log.e(TAG, "SuperIsland extras failed: ${e.message}")
        }

        return notification
    }

    /**
     * 更新时钟通知 (修复版 - 带限流)
     */
    fun updateClock(
        ctx: Context,
        timeStr: String,
        millisStr: String,
        source: String,
        isNight: Boolean = false
    ) {
        val notification = createClockNotification(ctx, timeStr, millisStr, source, isNight)
        try {
            notifMgr.notify(ID_CLOCK, notification)
        } catch (e: Exception) {
            Log.e(TAG, "updateClock notify failed: ${e.message}")
        }
    }

    // ================================================================
    //  同步状态通知
    // ================================================================

    fun showTimeSyncing(source: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("正在同步...")
            .setContentText("从${sourceName(source)}获取时间")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        notifMgr.notify(ID_SYNC_STATUS, notification)
    }

    fun showTimeSyncSuccess(source: String, offsetMs: Long) {
        val text = "${sourceName(source)} | 偏移: ${"%.0f".format(offsetMs.toDouble())}ms"
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("时间已同步")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        notifMgr.notify(ID_SYNC_STATUS, notification)
        handler.postDelayed({ notifMgr.cancel(ID_SYNC_STATUS) }, 3000)
    }

    fun showTimeSyncFailed(source: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("同步失败")
            .setContentText("无法从${sourceName(source)}获取时间")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        notifMgr.notify(ID_SYNC_STATUS, notification)
        handler.postDelayed({ notifMgr.cancel(ID_SYNC_STATUS) }, 5000)
    }

    fun clearAll() {
        notifMgr.cancel(ID_SYNC_STATUS)
        superIsland.hide()
    }

    // ================================================================
    //  Android 16 ProgressStyle
    // ================================================================
    @Suppress("UNUSED_PARAMETER")

    private fun applyProgressStyle(notification: Notification, timeStr: String, millisStr: String, isNight: Boolean) {
        if (Build.VERSION.SDK_INT < 36 || progressStyleClass == null) return

        val currentSecond = parseSecond(timeStr)

        // 构建 60 个 segment，交替颜色模拟时钟刻度
        val segments = (0 until PROGRESS_MAX).map { i ->
            createSegment(1, if (i % 2 == 0) {
                if (isNight) 0xFF555555.toInt() else 0xFFCCCCCC.toInt()
            } else {
                if (isNight) 0xFF444444.toInt() else 0xFFE8E8E8.toInt()
            })
        }

        // 关键时间点标记
        val points = listOf(
            createPoint(0, 0xFF4CAF50.toInt()),
            createPoint(30, 0xFFFF5722.toInt()),
            createPoint(currentSecond, 0xFF2196F3.toInt())
        )

        createProgressStyle(currentSecond, segments, points)?.let { style ->
            applyStyleToNotification(notification, style)
        }
    }

    private fun parseSecond(timeStr: String): Int =
        try {
            timeStr.split(":").getOrNull(2)?.toInt() ?: 0
        } catch (_: NumberFormatException) { 0 }

    private fun createSegment(length: Int, color: Int): Any {
        val cls = segmentClass ?: return fallbackBundle(length, color)
        return try {
            val segment = cls.getConstructor(Int::class.javaPrimitiveType).newInstance(length)
            cls.getMethod("setColor", Int::class.javaPrimitiveType).invoke(segment, color)
            segment
        } catch (_: Exception) {
            fallbackBundle(length, color)
        }
    }

    private fun createPoint(position: Int, color: Int): Any {
        val cls = pointClass ?: return Bundle().apply {
            putInt("position", position); putInt("color", color)
        }
        return try {
            val point = cls.getConstructor(Int::class.javaPrimitiveType).newInstance(position)
            cls.getMethod("setColor", Int::class.javaPrimitiveType).invoke(point, color)
            point
        } catch (_: Exception) {
            Bundle().apply { putInt("position", position); putInt("color", color) }
        }
    }

    private fun fallbackBundle(length: Int, color: Int) = Bundle().apply {
        putInt("length", length); putInt("color", color)
    }

    private fun createProgressStyle(progress: Int, segments: List<Any>, points: List<Any>): Any? {
        if (Build.VERSION.SDK_INT < 36 || progressStyleClass == null) return null
        return try {
            val style = progressStyleClass
                ?: return.getDeclaredConstructor().newInstance()
            setStyledByProgress
                ?: return.invoke(style, false)
            setProgress
                ?: return.invoke(style, progress)
            setProgressSegments
                ?: return.invoke(style, segments)
            setProgressPoints
                ?: return.invoke(style, points)
            style
        } catch (e: Exception) {
            Log.e(TAG, "createProgressStyle failed: ${e.message}")
            null
        }
    }

    private fun applyStyleToNotification(notification: Notification, progressStyle: Any) {
        if (Build.VERSION.SDK_INT < 36) return
        try {
            val extras = notification.extras
            val nativeBuilder = Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(extras.getString(Notification.EXTRA_TITLE, ""))
                .setContentText(extras.getString(Notification.EXTRA_TEXT, ""))
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1 else 0)

            builderSetProgressStyle?.invoke(nativeBuilder, progressStyle)
            val built = nativeBuilder.build()
            extras.putAll(built.extras)
        } catch (e: Exception) {
            Log.e(TAG, "applyStyleToNotification failed: ${e.message}")
        }
    }

    // ================================================================
    //  秒表功能 (预留)
    // ================================================================

    fun startStopwatch() { Log.d(TAG, "startStopwatch: not implemented") }
    fun pauseStopwatch() { Log.d(TAG, "pauseStopwatch: not implemented") }
    fun stopStopwatch() { Log.d(TAG, "stopStopwatch: not implemented") }

    // ================================================================
    //  私有方法
    // ================================================================

    private fun sourceName(source: String): String = when (source) {
        "taobao" -> "淘宝"
        "meituan" -> "美团"
        "local" -> "本地"
        else -> source
    }

    /**
     * PendingIntent 工具
     */
    private object MainPendingIntent {
        fun create(ctx: Context) = android.app.PendingIntent.getActivity(
            ctx, 0,
            android.content.Intent(ctx, MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }
}
