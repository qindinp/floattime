package com.floattime.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * IslandManager - 统一前台服务入口
 *
 * 三层渲染兜底:
 * 1. LiveIslandHandler  - Android 16+ AndroidX Window Extensions API
 * 2. HyperIslandHandler - MIUI HyperOS 焦点通知 extras
 * 3. NotificationHandler - 所有设备通知栏兜底
 *
 * @see <a href="https://developer.android.com/develop/ui/views/launch/live-island">Live Island</a>
 */
class IslandManager(private val context: Context) {

    companion object {
        private const val TAG = "IslandManager"
        const val CHANNEL_ID = "float_time_live_updates"
        private const val CHANNEL_NAME = "悬浮时间"
        private const val CHANNEL_DESC = "实时显示校准时间"
        const val NOTIFICATION_ID = 20240320
        const val SYNC_NOTIFICATION_ID = 20240321
        private var initialized = false
    }

    private val appContext = context.applicationContext
    private val notifMgr: NotificationManager by lazy {
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val handler = Handler(Looper.getMainLooper())

    // 三个子管理器
    private val liveIsland: LiveIslandHandler? = LiveIslandHandler.Factory.create(appContext, notifMgr)
    private val hyperIsland: HyperIslandHandler = HyperIslandHandler(appContext, notifMgr)
    private val notificationHandler: NotificationHandler by lazy {
        NotificationHandler(appContext, notifMgr)
    }

    init {
        if (!initialized) {
            createChannel()
            initialized = true
        }
        Log.d(TAG, "IslandManager init | API=${Build.VERSION.SDK_INT}" +
                " | LiveIsland=${liveIsland != null}" +
                " | HyperOS=${hyperIsland.isSupported}")
    }

    fun describe(): String {
        return when {
            liveIsland?.isActive() == true -> "LiveIsland"
            hyperIsland.isActive() -> "HyperIsland"
            else -> "Notification"
        }
    }

    fun createNotification(timeStr: String, millisStr: String, source: String, isNight: Boolean): Notification {
        return notificationHandler.build(timeStr, millisStr, source, isNight)
    }

    fun update(timeStr: String, millisStr: String, source: String, isNight: Boolean) {
        liveIsland?.update(timeStr, millisStr, isNight)
    }

    fun show(timeStr: String, millisStr: String, source: String, isNight: Boolean) {
        liveIsland?.show(timeStr, millisStr, source, isNight)
        hyperIsland.show(timeStr, millisStr, source)
    }

    fun hide() {
        liveIsland?.hide()
        hyperIsland.hide()
    }

    fun onThemeChanged(isNight: Boolean) {
        liveIsland?.onThemeChanged(isNight)
    }

    fun showSyncStatus(syncing: Boolean, source: String) {
        if (syncing) notificationHandler.showSyncPending(source)
        if (syncing) liveIsland?.showSyncPending(source)
    }

    fun showSyncResult(success: Boolean, source: String, offsetMs: Long) {
        notificationHandler.showSyncResult(success, source, offsetMs)
        liveIsland?.showSyncResult(success, source, offsetMs)
    }

    fun destroy() {
        liveIsland?.destroy()
        hyperIsland.destroy()
        notifMgr.cancel(SYNC_NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESC
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            setBypassDnd(true)
        }
        notifMgr.createNotificationChannel(ch)
    }

    // ==============================================================
    //  LiveIslandHandler - Android 16+ AndroidX Window Extensions API
    //  Android 15+ (API 35) / Android 16+ (API 36)
    //  Uses a package-level LhState object for reflection caching.
    // ==============================================================

    internal class LiveIslandHandler constructor(
        private val ctx: Context,
        private val nm: NotificationManager
    ) {
        private var isActive = false
        private var currentTimeStr = ""
        private var currentMillisStr = ""
        private var isNight = false
        private var currentSource = ""
        private val pendingUpdate = AtomicInteger(0)

        fun isActive() = isActive

        fun show(timeStr: String, millisStr: String, source: String, night: Boolean) {
            currentTimeStr = timeStr; currentMillisStr = millisStr
            currentSource = source; isNight = night
            if (!isActive) {
                try { register(); isActive = true }
                catch (e: Exception) {
                    Log.e(LhState.TAG, "show failed: ${e.message}"); isActive = false
                }
            }
        }

        fun update(timeStr: String, millisStr: String, night: Boolean) {
            currentTimeStr = timeStr; currentMillisStr = millisStr; isNight = night
            if (isActive) {
                val seq = pendingUpdate.incrementAndGet()
                Handler(Looper.getMainLooper()).postDelayed({
                    if (seq == pendingUpdate.get()) refreshContent()
                }, 500)
            }
        }

        fun hide() {
            if (isActive) {
                try { unregister() } catch (_: Exception) {}
                isActive = false
            }
        }

        fun onThemeChanged(night: Boolean) {
            isNight = night
            if (isActive) refreshContent()
        }

        fun showSyncPending(source: String) {
            Log.d(LhState.TAG, "Sync pending: $source")
        }

        fun showSyncResult(success: Boolean, source: String, offsetMs: Long) {
            Log.d(LhState.TAG, "Sync result: success=$success source=$source offset=${offsetMs}ms")
        }

        fun destroy() = hide()

        private fun register() {
            val wam = LhState.getWindowAreaManager() ?: return
            val presentation = createPresentation()
            val looper = Handler(Looper.getMainLooper())
            LhState.getRegisterMethod()?.invoke(wam, presentation, looper)
            Log.d(LhState.TAG, "Live Island registered")
        }

        private fun unregister() {
            val wam = LhState.getWindowAreaManager() ?: return
            try {
                val presentation = createPresentation()
                LhState.getUnregisterMethod()?.invoke(wam, presentation)
                Log.d(LhState.TAG, "Live Island unregistered")
            } catch (_: Exception) {}
        }

        private fun refreshContent() {
            if (!isActive) return
            try { unregister(); register() } catch (_: Exception) {}
        }

        private fun createPresentation(): Any {
            val iface = LhState.getWindowAreaPresentation()
                ?: throw IllegalStateException("WindowAreaPresentation class not loaded")
            return java.lang.reflect.Proxy.newProxyInstance(
                iface.classLoader, arrayOf(iface)
            ) { _, method, _ ->
                when (method.name) {
                    "getContent" -> createContentView()
                    "onOpening" -> Log.d(LhState.TAG, "Live Island opening")
                    "onClosed" -> { Log.d(LhState.TAG, "Live Island closed"); isActive = false }
                    "onClick" -> {
                        Log.d(LhState.TAG, "Live Island clicked")
                        val intent = Intent(ctx, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                    }
                    "onClose" -> Log.d(LhState.TAG, "Live Island close requested")
                }
                null
            }
        }

        private fun createContentView(): Bundle {
            return Bundle().apply {
                putString("title", currentTimeStr)
                putString("subtitle", ".$currentMillisStr $currentSource")
                putInt("textColor", if (isNight) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                putInt("iconType", 1)
                putBoolean("showMillis", true)
                putBoolean("showSeconds", true)
                putInt("style", if (isNight) 1 else 0)
            }
        }

        companion object Factory {
            fun create(c: Context, n: NotificationManager): LiveIslandHandler? =
                LhState.tryCreate(c, n)
        }
    }

    // ==============================================================
    //  HyperIslandHandler - MIUI HyperOS 焦点通知
    // ==============================================================

    private class HyperIslandHandler(
        private val ctx: Context,
        private val nm: NotificationManager
    ) {
        private val TAG = "HyperIslandHandler"
        private val FOCUS_TITLE = "miui.focus.title"
        private val FOCUS_CONTENT = "miui.focus.content"
        private val FOCUS_ICON = "miui.focus.icon"
        @Volatile private var cachedHyperOS: Boolean? = null
        private var _isShowing = false

        private val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isSupported: Boolean
            get() = isHyperOSDevice() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        fun isActive() = _isShowing

        fun show(timeStr: String, millisStr: String, source: String) {
            if (!isSupported) return
            try {
                val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(timeStr + millisStr)
                    .setContentText(source)
                    .setContentIntent(pi)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setForegroundServiceBehavior(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                        else NotificationCompat.FOREGROUND_SERVICE_DEFAULT
                    )
                    .build()

                val extras = n.extras
                extras.putString(FOCUS_TITLE, timeStr)
                extras.putString(FOCUS_CONTENT, "${source} ${millisStr}")
                extras.putInt(FOCUS_ICON, 1)
                extras.putString("miui.focus.indicator", "dot")
                extras.putBoolean("miui.focus.expanded", true)
                extras.putString("miui.focus.expanded.title", "悬浮时间")
                extras.putString("miui.focus.expanded.content", "$timeStr$millisStr\n$source\n点击打开设置")

                nm.notify(NOTIFICATION_ID, n)
                _isShowing = true
                Log.d(TAG, "HyperIsland shown: $timeStr $source")
            } catch (e: Exception) {
                Log.e(TAG, "show failed: ${e.message}")
            }
        }

        fun hide() {
            if (_isShowing) { nm.cancel(NOTIFICATION_ID); _isShowing = false }
        }

        fun destroy() = hide()

        private fun isHyperOSDevice(): Boolean {
            cachedHyperOS?.let { return it }
            var result = false
            try {
                val clazz = Class.forName("android.os.SystemProperties")
                val prop = clazz.getMethod("get", String::class.java)
                    .invoke(null, "ro.mi.os.version.incremental") as? String
                result = !prop.isNullOrEmpty()
            } catch (_: Exception) {
                val m = Build.MANUFACTURER.lowercase()
                result = m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
            }
            cachedHyperOS = result
            return result
        }
    }

    // ==============================================================
    //  NotificationHandler - 标准通知栏（所有设备兜底）
    // ==============================================================

    private inner class NotificationHandler(
        private val ctx: Context,
        private val nm: NotificationManager
    ) {
        private val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun build(timeStr: String, millisStr: String, source: String, isNight: Boolean): Notification {
            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(timeStr + millisStr)
                .setContentText(source)
                .setContentIntent(pi)
                .setPriority(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        NotificationCompat.PRIORITY_HIGH
                    else NotificationCompat.PRIORITY_LOW
                )
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setLocalOnly(true)
                .setForegroundServiceBehavior(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                    else NotificationCompat.FOREGROUND_SERVICE_DEFAULT
                )

            // Android 16 ProgressStyle Live Updates
            if (Build.VERSION.SDK_INT >= 36) {
                val second = try { timeStr.split(":").getOrNull(2)?.toInt() ?: 0 } catch (_: Exception) { 0 }
                val color = if (isNight) 0xFF4FC3F7.toInt() else 0xFF1976D2.toInt()
                builder.setProgress(60, second, false)
                Log.d("NotificationHandler", "ProgressStyle: second=$second")
            }

            return builder.build()
        }

        fun showSyncPending(source: String) {
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("正在同步...")
                .setContentText("从${srcName(source)}获取时间")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(SYNC_NOTIFICATION_ID, n)
        }

        fun showSyncResult(success: Boolean, source: String, offsetMs: Long) {
            nm.cancel(SYNC_NOTIFICATION_ID)
            if (!success) {
                val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("同步失败")
                    .setContentText("无法从${srcName(source)}获取时间")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build()
                nm.notify(SYNC_NOTIFICATION_ID, n)
                handler.postDelayed({ nm.cancel(SYNC_NOTIFICATION_ID) }, 5000)
            }
        }

        private fun srcName(s: String) = when (s) {
            "taobao" -> "淘宝"; "meituan" -> "美团"; else -> s
        }
    }
}

// ==============================================================
//  LhState - Package-level reflection state for LiveIslandHandler
//  Avoids companion-object-inside-inner-class restriction
// ==============================================================

object LhState {
    const val TAG = "LiveIslandHandler"

    private var windowAreaPresentation: Class<*>? = null
    private var windowAreaManager: Any? = null
    private var registerMethod: Method? = null
    private var unregisterMethod: Method? = null
    private var extensionVersion: Int = 0
    @Volatile private var checked = false
    @Volatile private var supported = false

    fun getWindowAreaManager() = windowAreaManager
    fun getWindowAreaPresentation() = windowAreaPresentation
    fun getRegisterMethod() = registerMethod
    fun getUnregisterMethod() = unregisterMethod

    internal fun tryCreate(c: Context, n: NotificationManager): IslandManager.LiveIslandHandler? {
        if (checked) return if (supported) IslandManager.LiveIslandHandler(c, n) else null
        checked = true
        try {
            val sdkInt = Build.VERSION.SDK_INT
            if (sdkInt >= 35) {
                windowAreaPresentation = Class.forName("androidx.window.area.WindowAreaPresentation")

                val atmClass = Class.forName("android.app.ActivityTaskManager")
                val atm = atmClass.getMethod("getInstance").invoke(null)
                val wacField = atmClass.getDeclaredField("mWindowAreaComponent")
                wacField.isAccessible = true
                val wac = wacField.get(atm) ?: return null

                if (sdkInt >= 36) {
                    val getExtVer = wac.javaClass.getMethod("getExtensionVersion")
                    extensionVersion = (getExtVer.invoke(wac) as? Int) ?: 0
                }

                val wamField = wac.javaClass.getDeclaredField("mWindowAreaManager")
                wamField.isAccessible = true
                windowAreaManager = wamField.get(wac)

                registerMethod = (windowAreaManager as Any).javaClass.getMethod(
                    "registerWindowAreaPresentation",
                    windowAreaPresentation!!,
                    android.os.Handler::class.java
                )
                unregisterMethod = (windowAreaManager as Any).javaClass.getMethod(
                    "unregisterWindowAreaPresentation",
                    windowAreaPresentation!!
                )
                supported = true
                Log.d(TAG, "Live Island supported (API $sdkInt, extVer=$extensionVersion)")
                return IslandManager.LiveIslandHandler(c, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Live Island check failed: ${e::class.simpleName}: ${e.message}")
        }
        Log.d(TAG, "Live Island not supported (API ${Build.VERSION.SDK_INT})")
        return null
    }
}
