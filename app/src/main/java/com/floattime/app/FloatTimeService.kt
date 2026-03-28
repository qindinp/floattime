package com.floattime.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 悬浮时间前台服务 (优化版 v2)
 *
 * 优化内容:
 * - ✅ ThreadLocal SimpleDateFormat: 消除 synchronized 锁竞争
 * - ✅ Timezone 显示名缓存: 主题切换时计算一次，避免每 tick 重新获取
 * - ✅ OkHttp 连接池: 替代 HttpURLConnection，连接复用
 * - ✅ ExecutorService: 替代 new Thread()，线程复用 + 队列管理
 * - ✅ stopForeground(STOP_FOREGROUND_REMOVE): 停止时正确移除通知
 * - ✅ User-Agent: HTTP 请求附加 UA
 * - ✅ 网络状态监听: 网络恢复时自动重新同步时间
 *
 * 使用通知栏显示实时校准时间
 */
class FloatTimeService : Service() {

    companion object {
        private const val TAG = "FloatTimeService"
        private const val PREFS_NAME = "FloatTimePrefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_OFFSET_MS = "offset_ms"
        private const val KEY_TIME_SOURCE = "time_source"

        private const val NOTIFICATION_ID = 20240320
        private const val UPDATE_INTERVAL_MS = 200L
        private const val CONNECT_TIMEOUT_MS = 8000L
        private const val READ_TIMEOUT_MS = 8000L
        private const val SAVE_THROTTLE_MS = 5000L
        private const val MIN_NOTIFICATION_UPDATE_INTERVAL_MS = 1000L
        private const val NOTIFICATION_PERSISTENCE_CHECK_INTERVAL_MS = 5000L

        // ✅ 优化: ThreadLocal SimpleDateFormat - 每线程独立实例，零锁竞争
        private val TIME_FORMATTER = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        }

        @Volatile
        private var sIsRunning = false

        fun isRunning(): Boolean = sIsRunning

        /** 计算夜间模式 */
        fun calcNightMode(themeMode: Int): Boolean = when (themeMode) {
            0 -> {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                hour >= 19 || hour < 7
            }
            2 -> true
            else -> false
        }
    }

    private lateinit var notifMgr: NotificationManager
    private lateinit var liveUpdateManager: LiveUpdateManager
    private lateinit var prefs: SharedPreferences
    private lateinit var handler: Handler

    private var timeSource: String = "taobao"
    private var offsetMs: Long = 0
    private val isSyncing = AtomicBoolean(false)
    private var currentTimeStr = ""
    private var currentMillisStr = ""
    private var themeMode: Int = 0
    private var isNightMode: Boolean = false

    // ✅ 优化: Timezone 显示名缓存（只在模式切换时更新）
    private var cachedTimezoneAbbr: String = ""

    private var lastSaveTime: Long = 0
    private var lastNotificationUpdateTime: Long = 0
    private var timeRunnable: Runnable? = null
    private var persistenceCheckRunnable: Runnable? = null

    // ✅ 优化: 网络状态监听器
    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                if (isNetworkAvailable()) {
                    Log.d(TAG, "Network restored, resyncing time...")
                    syncTime()
                }
            }
        }
    }

    // ✅ 优化: OkHttp 单例客户端（连接池，复用连接）
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ✅ 优化: ExecutorService 单例（单线程，避免并发问题）
    private val syncExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FloatTimeSync").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        sIsRunning = true
        handler = Handler(Looper.getMainLooper())
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        liveUpdateManager = LiveUpdateManager(this)
        loadPreferences()
        notifMgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // ✅ 优化: 注册网络状态监听
        val filter = android.content.IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(networkReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(networkReceiver, filter)
        }

        // 先启动时钟，让 currentTimeStr 有初值
        startClock()

        // 延迟 100ms 后再启动前台服务，确保通知内容已初始化
        handler.postDelayed({
            try {
                startForeground(NOTIFICATION_ID, createNotification())
                log("Foreground service started with initialized notification")
            } catch (e: Exception) {
                log("startForeground error: ${e.message}")
            }
        }, 100)

        // 启动通知持久化检查
        ensureNotificationPersistent()

        syncTime()

        log("FloatTimeService started, LiveUpdate supported: ${liveUpdateManager.isSupported()}")
    }

    private fun loadPreferences() {
        themeMode = prefs.getInt(KEY_THEME_MODE, 0)
        offsetMs = prefs.getLong(KEY_OFFSET_MS, 0)
        timeSource = prefs.getString(KEY_TIME_SOURCE, "taobao") ?: "taobao"
        isNightMode = calcNightMode(themeMode)
        // ✅ 优化: 加载时缓存 Timezone 显示名
        cachedTimezoneAbbr = getTimezoneAbbr()
    }

    /** ✅ 优化: 获取时区缩写（缓存） */
    private fun getTimezoneAbbr(): String {
        return try {
            val tz = java.util.TimeZone.getDefault()
            tz.getDisplayName(isNightMode, java.util.TimeZone.SHORT, Locale.getDefault())
        } catch (_: Exception) {
            ""
        }
    }

    private fun createNotification(): Notification = try {
        val timeStr = currentTimeStr.ifEmpty { "--:--:--" }
        val millisStr = if (currentMillisStr.isEmpty()) ".000" else ".$currentMillisStr"

        liveUpdateManager.createClockNotification(this, timeStr, millisStr, sourceDisplayName, isNightMode)
    } catch (e: Exception) {
        log("createNotification error: ${e.message}")
        createFallbackNotification()
    }

    private fun createFallbackNotification(): Notification =
        NotificationCompat.Builder(this, LiveUpdateManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("悬浮时间")
            .setContentText("服务运行中")
            .setOngoing(true)
            .build()

    private fun startClock() {
        timeRunnable = object : Runnable {
            override fun run() {
                updateTime()
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }.also { handler.post(it) }
    }

    private fun updateTime() {
        try {
            val now = System.currentTimeMillis() + offsetMs

            // ✅ 优化: ThreadLocal SimpleDateFormat - 无锁，直接调用
            currentTimeStr = TIME_FORMATTER.get().format(Date(now))
            currentMillisStr = String.format(Locale.getDefault(), "%03d", now % 1000)

            // ✅ 优化: 主题/Timezone 切换检查（只在变化时重新计算）
            val newNight = calcNightMode(themeMode)
            if (newNight != isNightMode) {
                isNightMode = newNight
                cachedTimezoneAbbr = getTimezoneAbbr()
                log("Theme switched: ${if (isNightMode) "dark" else "light"}")
            }

            updateNotification()
        } catch (e: Exception) {
            log("updateTime error: ${e.message}")
        }
    }

    /**
     * 更新通知 (限流版)
     */
    private fun updateNotification() {
        if (!::notifMgr.isInitialized) return

        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateTime < MIN_NOTIFICATION_UPDATE_INTERVAL_MS) {
            return
        }
        lastNotificationUpdateTime = now

        try {
            val timeStr = currentTimeStr.ifEmpty { "--:--:--" }
            val millisStr = if (currentMillisStr.isEmpty()) ".000" else ".$currentMillisStr"

            liveUpdateManager.updateClock(this, timeStr, millisStr, sourceDisplayName, isNightMode)
        } catch (e: Exception) {
            log("updateNotification error: ${e.message}")
        }
    }

    /**
     * 通知持久化检查
     * 定期检查通知是否被移除，如果被移除则重新启动前台服务
     */
    private fun ensureNotificationPersistent() {
        persistenceCheckRunnable = object : Runnable {
            override fun run() {
                if (sIsRunning && ::notifMgr.isInitialized) {
                    try {
                        val timeStr = currentTimeStr.ifEmpty { "--:--:--" }
                        val millisStr = if (currentMillisStr.isEmpty()) ".000" else ".$currentMillisStr"
                        val notification = liveUpdateManager.createClockNotification(
                            this@FloatTimeService, timeStr, millisStr, sourceDisplayName, isNightMode
                        )
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (e: Exception) {
                        log("ensureNotificationPersistent error: ${e.message}")
                    }
                }
                if (sIsRunning) {
                    handler.postDelayed(this, NOTIFICATION_PERSISTENCE_CHECK_INTERVAL_MS)
                }
            }
        }.also { handler.postDelayed(it, NOTIFICATION_PERSISTENCE_CHECK_INTERVAL_MS) }
    }

    /** ✅ 优化: 网络可用性检查 */
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun syncTime() {
        if (!isSyncing.compareAndSet(false, true)) return

        if (timeSource == "local") {
            offsetMs = 0
            saveOffset()
            liveUpdateManager.showTimeSyncSuccess("local", 0)
            log("Local time mode, offset=0")
            isSyncing.set(false)
            return
        }

        log("Syncing time from: $timeSource")
        liveUpdateManager.showTimeSyncing(timeSource)

        // ✅ 优化: ExecutorService 替代 new Thread()
        syncExecutor.submit {
            try {
                doSync()
            } finally {
                isSyncing.set(false)
            }
        }
    }

    /** ✅ 优化: OkHttp 同步方法 */
    private fun doSync() {
        val url = if (timeSource == "taobao")
            "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
        else
            "https://api.meituan.com/nationalTimestamp"

        val localBefore = System.currentTimeMillis()

        // ✅ 优化: OkHttp + User-Agent
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FloatTime/" + getVersion(this))
            .header("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    postSyncFailed()
                    return
                }

                val responseBody = response.body?.string() ?: run {
                    postSyncFailed()
                    return
                }

                val localAfter = System.currentTimeMillis()
                val localMid = (localBefore + localAfter) / 2
                val serverTime = parseServerTime(responseBody)

                if (serverTime > 0) {
                    offsetMs = serverTime - localMid
                    saveOffset()
                    postSyncSuccess()
                    log("Time synced: offset=$offsetMs ms")
                } else {
                    postSyncFailed()
                    log("Failed to parse server time")
                }
            }
        } catch (e: IOException) {
            postSyncFailed()
            log("syncTime error: ${e.message}")
        }
    }

    private fun postSyncSuccess() {
        handler.post {
            try {
                liveUpdateManager.showTimeSyncSuccess(timeSource, offsetMs)
            } catch (_: Exception) {}
        }
    }

    private fun postSyncFailed() {
        handler.post {
            try {
                liveUpdateManager.showTimeSyncFailed(timeSource)
            } catch (_: Exception) {}
        }
    }

    private fun parseServerTime(response: String): Long {
        try {
            val json = JSONObject(response)

            // data.t (淘宝格式)
            if (json.has("data")) {
                val data = json.getJSONObject("data")
                if (data.has("t")) {
                    val ts = data.getString("t").toLongOrNull() ?: 0
                    if (ts > 0) return if (ts < 10000000000L) ts * 1000 else ts
                }
            }

            // t (直接字段)
            if (json.has("t")) {
                val ts = json.getLong("t")
                if (ts > 0) return if (ts < 10000000000L) ts * 1000 else ts
            }

            // timestamp (美团格式)
            if (json.has("timestamp")) {
                val ts = json.getLong("timestamp")
                if (ts > 0) return if (ts < 10000000000L) ts * 1000 else ts
            }
        } catch (e: Exception) {
            log("parseServerTime error: ${e.message}")
        }
        return 0
    }

    /** SharedPreferences 写入节流 */
    private fun saveOffset() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < SAVE_THROTTLE_MS) return
        lastSaveTime = now
        prefs.edit().putLong(KEY_OFFSET_MS, offsetMs).apply()
    }

    private val sourceDisplayName: String
        get() = when (timeSource) {
            "taobao" -> "淘宝时间"
            "meituan" -> "美团时间"
            else -> "本地时间"
        }

    private fun getVersion(ctx: Context): String {
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    private fun log(msg: String) = Log.d(TAG, msg)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                // ✅ 优化: 停止时正确移除前台通知
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            "CHANGE_THEME" -> {
                val mode = intent.getIntExtra("mode", 0)
                themeMode = mode
                prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
                isNightMode = calcNightMode(mode)
                cachedTimezoneAbbr = getTimezoneAbbr()
                log("Theme changed to: $mode, night=$isNightMode")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        log("onDestroy called")
        sIsRunning = false

        // ✅ 优化: 注销网络监听
        try {
            unregisterReceiver(networkReceiver)
        } catch (_: Exception) {}

        if (::liveUpdateManager.isInitialized) {
            liveUpdateManager.clearAll()
        }

        timeRunnable?.let { handler.removeCallbacks(it) }
        persistenceCheckRunnable?.let { handler.removeCallbacks(it) }

        // 退出前保存偏移量
        prefs.edit().putLong(KEY_OFFSET_MS, offsetMs).apply()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
