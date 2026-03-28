package com.floattime.app

import android.app.Service
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.TimeZone
import androidx.core.app.NotificationCompat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FloatTimeService - 悬浮时间前台服务 (Live Island 集成版 v3)
 *
 * 三层前台服务架构:
 * - LiveIslandHandler: Android 16+ AndroidX Window Extensions
 * - HyperIslandHandler: MIUI HyperOS 焦点通知
 * - NotificationHandler: 所有设备通知栏兜底
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
        private const val NOTIFICATION_UPDATE_MIN_INTERVAL_MS = 500L
        private const val NOTIFICATION_PERSISTENCE_CHECK_INTERVAL_MS = 3000L

        // ThreadLocal SimpleDateFormat: 每线程独立实例，零锁竞争
        private val TIME_FORMATTER = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        }

        // 毫秒格式化
        private val MS_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("SSS", Locale.US)
        }

        @Volatile
        private var sIsRunning = false

        fun isRunning(): Boolean = sIsRunning

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
    private lateinit var islandManager: IslandManager
    private lateinit var prefs: SharedPreferences
    private lateinit var handler: Handler

    private var timeSource: String = "taobao"
    private var offsetMs: Long = 0
    private val isSyncing = AtomicBoolean(false)
    private var currentTimeStr: String = ""
    private var currentMillisStr: String = ""
    private var themeMode: Int = 0
    private var isNightMode: Boolean = false
    private var cachedTimezoneAbbr: String = ""
    private var lastSaveTime: Long = 0
    private var lastNotificationUpdateTime: Long = 0
    private var timeRunnable: Runnable? = null
    private var persistenceCheckRunnable: Runnable? = null

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

    // OkHttp 单例客户端
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ExecutorService 单线程池
    private val syncExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FloatTimeSync").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        sIsRunning = true
        handler = Handler(Looper.getMainLooper())
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        islandManager = IslandManager(this)
        loadPreferences()
        notifMgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(networkReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(networkReceiver, filter)
        }

        startClock()

        handler.postDelayed({
            try {
                startForeground(NOTIFICATION_ID, createNotification())
                islandManager.show(currentTimeStr, currentMillisStr, sourceDisplayName, isNightMode)
                Log.d(TAG, "Foreground + Island started")
            } catch (e: Exception) {
                Log.e(TAG, "startForeground error: ${e.message}")
            }
        }, 100)

        ensureNotificationPersistent()
        syncTime()
        Log.d(TAG, "FloatTimeService started | Island: ${islandManager.describe()}")
    }

    private fun loadPreferences() {
        themeMode = prefs.getInt(KEY_THEME_MODE, 0)
        offsetMs = prefs.getLong(KEY_OFFSET_MS, 0)
        timeSource = prefs.getString(KEY_TIME_SOURCE, "taobao") ?: "taobao"
        isNightMode = calcNightMode(themeMode)
        cachedTimezoneAbbr = getTimezoneAbbr()
    }

    private fun getTimezoneAbbr(): String {
        return try {
            TimeZone.getDefault().getDisplayName(isNightMode, TimeZone.SHORT, Locale.getDefault())
        } catch (_: Exception) {
            ""
        }
    }

    private fun createNotification(): Notification {
        val timeStr = currentTimeStr.ifEmpty { "--:--:--" }
        val millisStr = if (currentMillisStr.isEmpty()) ".000" else ".$currentMillisStr"
        return try {
            islandManager.createNotification(timeStr, millisStr, sourceDisplayName, isNightMode)
        } catch (e: Exception) {
            Log.e(TAG, "createNotification error: ${e.message}")
            createFallbackNotification()
        }
    }

    private fun createFallbackNotification(): Notification {
        return NotificationCompat.Builder(this, IslandManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("悬浮时间")
            .setContentText("服务运行中")
            .setOngoing(true)
            .build()
    }

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
            currentTimeStr = TIME_FORMATTER.get().format(Date(now))
            currentMillisStr = MS_FORMAT.get().format(Date(now))

            val newNight = calcNightMode(themeMode)
            if (newNight != isNightMode) {
                isNightMode = newNight
                cachedTimezoneAbbr = getTimezoneAbbr()
                islandManager.onThemeChanged(isNightMode)
                Log.d(TAG, "Theme switched: ${if (isNightMode) "dark" else "light"}")
            }

            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "updateTime error: ${e.message}")
        }
    }

    private fun updateNotification() {
        if (!::notifMgr.isInitialized) return
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateTime < NOTIFICATION_UPDATE_MIN_INTERVAL_MS) return
        lastNotificationUpdateTime = now

        try {
            val timeStr = currentTimeStr.ifEmpty { "--:--:--" }
            val millisStr = if (currentMillisStr.isEmpty()) ".000" else ".$currentMillisStr"
            val notification = islandManager.createNotification(timeStr, millisStr, sourceDisplayName, isNightMode)
            notifMgr.notify(NOTIFICATION_ID, notification)
            islandManager.update(timeStr, millisStr, sourceDisplayName, isNightMode)
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification error: ${e.message}")
        }
    }

    private fun ensureNotificationPersistent() {
        persistenceCheckRunnable = object : Runnable {
            override fun run() {
                if (sIsRunning && ::notifMgr.isInitialized) {
                    try {
                        val timeStr = currentTimeStr.ifEmpty { "--:--:--" }
                        val millisStr = if (currentMillisStr.isEmpty()) ".000" else ".$currentMillisStr"
                        val notification = islandManager.createNotification(
                            timeStr, millisStr, sourceDisplayName, isNightMode
                        )
                        startForeground(NOTIFICATION_ID, notification)
                        islandManager.show(timeStr, millisStr, sourceDisplayName, isNightMode)
                    } catch (e: Exception) {
                        Log.e(TAG, "ensureNotificationPersistent error: ${e.message}")
                    }
                }
                if (sIsRunning) {
                    handler.postDelayed(this, NOTIFICATION_PERSISTENCE_CHECK_INTERVAL_MS)
                }
            }
        }.also { handler.postDelayed(it, NOTIFICATION_PERSISTENCE_CHECK_INTERVAL_MS) }
    }

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
            islandManager.showSyncResult(true, "local", 0)
            isSyncing.set(false)
            return
        }

        islandManager.showSyncStatus(true, timeSource)
        syncExecutor.submit {
            try {
                doSync()
            } finally {
                isSyncing.set(false)
            }
        }
    }

    private fun doSync() {
        val url = if (timeSource == "taobao")
            "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
        else
            "https://api.meituan.com/nationalTimestamp"

        val localBefore = System.currentTimeMillis()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FloatTime/$versionName")
            .header("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    postSyncFailed(); return
                }
                val body = response.body?.string() ?: run { postSyncFailed(); return }
                val localAfter = System.currentTimeMillis()
                val localMid = (localBefore + localAfter) / 2
                val serverTime = parseServerTime(body)
                if (serverTime > 0) {
                    offsetMs = serverTime - localMid
                    saveOffset()
                    postSyncSuccess()
                    Log.d(TAG, "Time synced: offset=$offsetMs ms")
                } else {
                    postSyncFailed()
                }
            }
        } catch (e: IOException) {
            postSyncFailed()
            Log.e(TAG, "syncTime error: ${e.message}")
        }
    }

    private fun postSyncSuccess() {
        handler.post {
            try { islandManager.showSyncResult(true, timeSource, offsetMs) } catch (_: Exception) {}
        }
    }

    private fun postSyncFailed() {
        handler.post {
            try { islandManager.showSyncResult(false, timeSource, 0) } catch (_: Exception) {}
        }
    }

    private fun parseServerTime(response: String): Long {
        try {
            val json = JSONObject(response)
            if (json.has("data")) {
                val data = json.getJSONObject("data")
                if (data.has("t")) {
                    val ts = data.getString("t").toLongOrNull() ?: 0
                    if (ts > 0) return if (ts < 10000000000L) ts * 1000 else ts
                }
            }
            if (json.has("t")) {
                val ts = json.getLong("t")
                if (ts > 0) return if (ts < 10000000000L) ts * 1000 else ts
            }
            if (json.has("timestamp")) {
                val ts = json.getLong("timestamp")
                if (ts > 0) return if (ts < 10000000000L) ts * 1000 else ts
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseServerTime error: ${e.message}")
        }
        return 0
    }

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

    private val versionName: String
        get() = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }

    private fun log(msg: String) = Log.d(TAG, msg)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                islandManager.hide()
                stopSelf()
            }
            "CHANGE_THEME" -> {
                val mode = intent.getIntExtra("mode", 0)
                themeMode = mode
                prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
                isNightMode = calcNightMode(mode)
                cachedTimezoneAbbr = getTimezoneAbbr()
                islandManager.onThemeChanged(isNightMode)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sIsRunning = false
        try { unregisterReceiver(networkReceiver) } catch (_: Exception) {}
        if (::islandManager.isInitialized) islandManager.destroy()
        timeRunnable?.let { handler.removeCallbacks(it) }
        persistenceCheckRunnable?.let { handler.removeCallbacks(it) }
        prefs.edit().putLong(KEY_OFFSET_MS, offsetMs).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
