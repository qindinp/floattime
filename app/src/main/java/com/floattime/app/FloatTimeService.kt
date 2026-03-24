package com.floattime.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 悬浮时间前台服务
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
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
        private const val SAVE_THROTTLE_MS = 5000L

        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
    private var lastSaveTime: Long = 0
    private var timeRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        sIsRunning = true
        handler = Handler(Looper.getMainLooper())
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        liveUpdateManager = LiveUpdateManager(this)
        loadPreferences()
        notifMgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        startForeground(NOTIFICATION_ID, createNotification())
        startClock()
        syncTime()

        log("FloatTimeService started, LiveUpdate supported: ${liveUpdateManager.isSupported()}")
    }

    private fun loadPreferences() {
        themeMode = prefs.getInt(KEY_THEME_MODE, 0)
        offsetMs = prefs.getLong(KEY_OFFSET_MS, 0)
        timeSource = prefs.getString(KEY_TIME_SOURCE, "taobao") ?: "taobao"
        isNightMode = calcNightMode(themeMode)
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
            val date = Date(now)

            currentTimeStr = synchronized(TIME_FORMAT) { TIME_FORMAT.format(date) }
            currentMillisStr = String.format(Locale.getDefault(), "%03d", now % 1000)

            // 主题切换检查
            val newNight = calcNightMode(themeMode)
            if (newNight != isNightMode) {
                isNightMode = newNight
                log("Theme switched: ${if (isNightMode) "dark" else "light"}")
            }

            updateNotification()
        } catch (e: Exception) {
            log("updateTime error: ${e.message}")
        }
    }

    private fun updateNotification() {
        if (!::notifMgr.isInitialized) return
        try {
            val timeStr = currentTimeStr.ifEmpty { "--:--:--" }
            val millisStr = if (currentMillisStr.isEmpty()) ".000" else ".$currentMillisStr"

            liveUpdateManager.updateClock(this, timeStr, millisStr, sourceDisplayName, isNightMode)
        } catch (e: Exception) {
            log("updateNotification error: ${e.message}")
        }
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

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = if (timeSource == "taobao")
                    "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
                else
                    "https://api.meituan.com/nationalTimestamp"

                val localBefore = System.currentTimeMillis()

                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val localAfter = System.currentTimeMillis()
                    val localMid = (localBefore + localAfter) / 2
                    val serverTime = parseServerTime(response)

                    if (serverTime > 0) {
                        offsetMs = serverTime - localMid
                        saveOffset()
                        liveUpdateManager.showTimeSyncSuccess(timeSource, offsetMs)
                        log("Time synced: offset=$offsetMs ms")
                    } else {
                        liveUpdateManager.showTimeSyncFailed(timeSource)
                        log("Failed to parse server time")
                    }
                } else {
                    liveUpdateManager.showTimeSyncFailed(timeSource)
                    log("HTTP error: ${conn.responseCode}")
                }
            } catch (e: Exception) {
                liveUpdateManager.showTimeSyncFailed(timeSource)
                log("syncTime error: ${e.message}")
            } finally {
                conn?.disconnect()
                isSyncing.set(false)
            }
        }.start()
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

    private fun log(msg: String) = Log.d(TAG, msg)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> stopSelf()
            "CHANGE_THEME" -> {
                val mode = intent.getIntExtra("mode", 0)
                themeMode = mode
                prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
                isNightMode = calcNightMode(mode)
                log("Theme changed to: $mode, night=$isNightMode")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        log("onDestroy called")
        sIsRunning = false

        if (::liveUpdateManager.isInitialized) {
            liveUpdateManager.clearAll()
        }

        timeRunnable?.let { handler.removeCallbacks(it) }

        // 退出前保存偏移量
        prefs.edit().putLong(KEY_OFFSET_MS, offsetMs).apply()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
