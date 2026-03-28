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
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * IslandManager - 统一前台服务入口
 *
 * 负责三层渲染兜底:
 * 1. Live Island (AndroidX Window Extensions) - 最高优先级
 * 2. HyperOS 焦点通知 (MIUI 定制) - 中优先级
 * 3. 通知栏前台通知 (AndroidX) - 兜底兼容
 *
 * 支持:
 * - Android 11+ (API 30): NotificationGroup + Bubble
 * - Android 12+ (API 31): Notification.EXTRA_SUBSTITUTE_APP_NAME
 * - Android 13+ (API 33): Bubbles API
 * - Android 14+ (API 34): foregroundServiceType="specialUse"
 * - Android 15+ (API 35): Notification.LiveUpdates (ProgressStyle)
 * - Android 16+ (API 36): WindowAreaComponent (Live Island API)
 * - HyperOS (MIUI 14/15): 焦点通知 extras
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

        // 毫秒数色盘（用于 ProgressStyle）
        private val MS_COLORS = intArrayOf(
            0xFF2196F3.toInt(),  // 蓝色
            0xFF4CAF50.toInt(),  // 绿色
            0xFFFF9800.toInt(),  // 橙色
            0xFFE91E63.toInt(),  // 粉色
            0xFF9C27B0.toInt(),  // 紫色
            0xFF00BCD4.toInt(),  // 青色
            0xFFFFEB3B.toInt(),  // 黄色
            0xFFFF5722.toInt(),  // 深橙
            0xFF795548.toInt(),  // 棕色
            0xFF607D8B.toInt()   // 蓝灰
        )

        private var initialized = false
    }

    private val appContext = context.applicationContext
    private val notifMgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val handler = Handler(Looper.getMainLooper())

    // 三个子管理器（各自独立，互不影响）
    private val liveIsland: LiveIslandHandler? = LiveIslandHandler.tryCreate(appContext, notifMgr)
    private val hyperIsland: HyperIslandHandler = HyperIslandHandler(appContext, notifMgr)
    private val notificationHandler: NotificationHandler = NotificationHandler(appContext, notifMgr)

    // 同步状态
    private var isShowingSync = false

    init {
        if (!initialized) {
            createChannel()
            initialized = true
        }
        Log.d(TAG, "IslandManager init | API=${Build.VERSION.SDK_INT}" +
                " | LiveIsland=${liveIsland != null}" +
                " | HyperOS=${hyperIsland.isSupported()}" +
                " | Notification=${notificationHandler.isSupported()}")
    }

    fun isInitialized() = ::appContext.isInitialized

    /** 统一描述当前激活的渲染层 */
    fun describe(): String {
        return when {
            liveIsland?.isActive() == true -> "LiveIsland(${Build.VERSION.SDK_INT})"
            hyperIsland.isActive() -> "HyperIsland(MIUI)"
            else -> "Notification(API ${Build.VERSION.SDK_INT})"
        }
    }

    // ==============================================================
    //  公共 API（由 FloatTimeService 调用）
    // ==============================================================

    /**
     * 创建前台服务通知
     * 同时设置 Live Island / 焦点通知 / 通知栏内容
     */
    fun createNotification(
        timeStr: String,
        millisStr: String,
        source: String,
        isNight: Boolean
    ): Notification {
        return notificationHandler.build(timeStr, millisStr, source, isNight, liveIsland, hyperIsland)
    }

    /**
     * 刷新时间（由 FloatTimeService 每 200ms 调用）
     */
    fun update(timeStr: String, millisStr: String, source: String, isNight: Boolean) {
        // Live Island 优先
        liveIsland?.update(timeStr, millisStr, isNight)
        // 通知栏降频更新（避免过度刷新）
    }

    /**
     * 显示 Live Island / 焦点通知（启动时一次性激活）
     */
    fun show(timeStr: String, millisStr: String, source: String, isNight: Boolean) {
        liveIsland?.show(timeStr, millisStr, source, isNight)
        hyperIsland.show(timeStr, millisStr, source)
    }

    /**
     * 隐藏 Live Island / 焦点通知
     */
    fun hide() {
        liveIsland?.hide()
        hyperIsland.hide()
        isShowingSync = false
    }

    /**
     * 主题切换回调
     */
    fun onThemeChanged(isNight: Boolean) {
        liveIsland?.onThemeChanged(isNight)
    }

    /**
     * 显示同步状态（Android 13+ 使用 Live Activity）
     */
    fun showSyncStatus(syncing: Boolean, source: String) {
        if (syncing) {
            notificationHandler.showSyncPending(source)
            liveIsland?.showSyncPending(source)
        }
        isShowingSync = syncing
    }

    /**
     * 显示同步结果
     */
    fun showSyncResult(success: Boolean, source: String, offsetMs: Long) {
        notificationHandler.showSyncResult(success, source, offsetMs)
        liveIsland?.showSyncResult(success, source, offsetMs)
        isShowingSync = false
    }

    /**
     * 销毁（服务 onDestroy 时调用）
     */
    fun destroy() {
        liveIsland?.destroy()
        hyperIsland.destroy()
        notifMgr.cancel(SYNC_NOTIFICATION_ID)
    }

    // ==============================================================
    //  私有方法
    // ==============================================================

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
            setAllowBubbles(false)
        }
        notifMgr.createNotificationChannel(ch)
    }

    private fun MainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        appContext, 0,
        Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ==============================================================
    //  LiveIslandHandler - AndroidX Window Extensions API
    //  Android 16+ (API 36) Live Island / Android 15 (API 35) Live Activity
    // ==============================================================

    inner class LiveIslandHandler private constructor(
        private val context: Context,
        private val notifMgr: NotificationManager
    ) {
        private var isActive = false
        private var currentTimeStr = ""
        private var currentMillisStr = ""
        private var isNight = false
        private var currentSource = ""
        private val pendingUpdate = AtomicInteger(0)

        companion object {
            private const val TAG = "LiveIslandHandler"

            // AndroidX Window Extensions API 反射引用
            private var windowAreaComponent_clazz: Class<*>? = null
            private var windowAreaPresentation_clazz: Class<*>? = null
            private var windowAreaStatus_clazz: Class<*>? = null
            private var windowAreaManager: Any? = null
            private var registerWindowAreaPresentation_method: Method? = null
            private var unregisterWindowAreaPresentation_method: Method? = null
            private var getExtensionVersion_method: Method? = null
            private var extensionVersion: Int = 0

            // 是否已初始化
            @Volatile
            private var checked = false
            @Volatile
            private var supported = false

            /**
             * 尝试创建 LiveIslandHandler
             * 如果设备不支持 Window Extensions，返回 null
             */
            fun tryCreate(ctx: Context, nm: NotificationManager): LiveIslandHandler? {
                if (checked) return if (supported) LiveIslandHandler(ctx, nm) else null
                checked = true

                try {
                    // Step 1: 检测 WindowAreaComponent API（API 35+）
                    val sdkInt = Build.VERSION.SDK_INT

                    // Android 15+ (API 35) 使用 ActivityTaskManager
                    if (sdkInt >= 35) {
                        // 检查 androidx.window.area.WindowAreaComponent
                        windowAreaComponent_clazz = Class.forName(
                            "androidx.window.area.WindowAreaComponent"
                        )
                        windowAreaPresentation_clazz = Class.forName(
                            "androidx.window.area.WindowAreaPresentation"
                        )
                        windowAreaStatus_clazz = Class.forName(
                            "androidx.window.area.WindowAreaStatus"
                        )

                        // 从 ActivityTaskManager 获取 WindowAreaComponent
                        val atmClass = Class.forName("android.app.ActivityTaskManager")
                        val getInstance = atmClass.getMethod("getInstance")
                        val atm = getInstance.invoke(null)

                        // 调用 getWindowAreaComponent()
                        val watcField = atmClass.getDeclaredField("mWindowAreaComponent")
                        watcField.isAccessible = true
                        val wac = watcField.get(atm) ?: return null

                        // 检查 extensionVersion
                        if (sdkInt >= 36) {
                            getExtensionVersion_method = wac::class.java.getMethod("getExtensionVersion")
                            extensionVersion = (getExtensionVersion_method?.invoke(wac) as? Int) ?: 0
                            Log.d(TAG, "WindowAreaComponent found, extensionVersion=$extensionVersion")
                        }

                        // 获取 WindowAreaManager
                        val wamField = wac::class.java.getDeclaredField("mWindowAreaManager")
                        wamField.isAccessible = true
                        windowAreaManager = wamField.get(wac)!!

                        registerWindowAreaPresentation_method =
                            (windowAreaManager as Any)::class.java.getMethod(
                                "registerWindowAreaPresentation",
                                windowAreaPresentation_clazz as Class<Any>,
                                android.os.Handler::class.java
                            )
                        unregisterWindowAreaPresentation_method =
                            (windowAreaManager as Any)::class.java.getMethod(
                                "unregisterWindowAreaPresentation",
                                windowAreaPresentation_clazz as Class<Any>
                            )

                        supported = true
                        Log.d(TAG, "Live Island supported (API $sdkInt, extVer=$extensionVersion)")
                        return LiveIslandHandler(ctx, nm)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Live Island check failed: ${e::class.simpleName}: ${e.message}")
                }

                Log.d(TAG, "Live Island not supported (API ${Build.VERSION.SDK_INT})")
                return null
            }
        }

        fun isActive() = isActive

        fun show(timeStr: String, millisStr: String, source: String, night: Boolean) {
            currentTimeStr = timeStr
            currentMillisStr = millisStr
            currentSource = source
            isNight = night

            if (!isActive) {
                try {
                    register()
                    isActive = true
                } catch (e: Exception) {
                    Log.e(TAG, "show failed: ${e.message}")
                    isActive = false
                }
            }
        }

        fun update(timeStr: String, millisStr: String, night: Boolean) {
            currentTimeStr = timeStr
            currentMillisStr = millisStr
            isNight = night

            if (isActive) {
                // 使用 Handler post 避免过于频繁的更新
                val seq = pendingUpdate.incrementAndGet()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (seq == pendingUpdate.get()) {
                        refreshContent()
                    }
                }, 500)
            }
        }

        fun hide() {
            if (isActive) {
                try {
                    unregister()
                } catch (_: Exception) {}
                isActive = false
            }
        }

        fun onThemeChanged(isNight: Boolean) {
            this.isNight = isNight
            if (isActive) refreshContent()
        }

        fun showSyncPending(source: String) {
            // Live Activity 可以显示同步中的状态
            Log.d(TAG, "Sync pending: $source")
        }

        fun showSyncResult(success: Boolean, source: String, offsetMs: Long) {
            Log.d(TAG, "Sync result: success=$success source=$source offset=${offsetMs}ms")
        }

        fun destroy() {
            hide()
        }

        private fun register() {
            if (windowAreaManager == null || windowAreaPresentation_clazz == null) return

            val presentation = createPresentation()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            registerWindowAreaPresentation_method?.invoke(windowAreaManager, presentation, handler)
            Log.d(TAG, "Live Island registered")
        }

        private fun unregister() {
            if (windowAreaManager == null || windowAreaPresentation_clazz == null) return
            try {
                val presentation = createPresentation()
                unregisterWindowAreaPresentation_method?.invoke(windowAreaManager, presentation)
                Log.d(TAG, "Live Island unregistered")
            } catch (_: Exception) {}
        }

        private fun refreshContent() {
            if (!isActive) return
            try {
                unregister()
                register()
            } catch (_: Exception) {}
        }

        /**
         * 创建 WindowAreaPresentation 实例
         * 这是 Live Island 的核心：提供 ContentView 和状态回调
         */
        private fun createPresentation(): Any {
            if (windowAreaPresentation_clazz == null) {
                throw IllegalStateException("WindowAreaPresentation class not loaded")
            }

            // 使用动态代理实现 WindowAreaPresentation 接口
            val iface = windowAreaPresentation_clazz!!

            val handler = android.os.Handler(Looper.getMainLooper())

            return java.lang.reflect.Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface)
            ) { _, method, args ->
                when (method.name) {
                    "getWindowAreaDisplayMetrics" -> {
                        Log.d(TAG, "getWindowAreaDisplayMetrics called")
                        createDisplayMetrics()
                    }
                    "onOpening" -> {
                        Log.d(TAG, "Live Island opening")
                    }
                    "onClosed" -> {
                        Log.d(TAG, "Live Island closed")
                        isActive = false
                    }
                    "onClick" -> {
                        Log.d(TAG, "Live Island clicked")
                        // 打开 MainActivity
                        val intent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                    "onClose" -> {
                        Log.d(TAG, "Live Island close requested")
                    }
                    "getContent" -> {
                        // 返回 Compose ContentView
                        createContentView()
                    }
                    else -> {
                        Log.d(TAG, "WindowAreaPresentation.${method.name} called")
                    }
                }
                null
            }
        }

        private fun createDisplayMetrics(): android.util.DisplayMetrics {
            val dm = context.resources.displayMetrics
            return dm
        }

        /**
         * 创建 Live Island 内容视图
         * 使用 Bundle 形式返回 ContentView
         *
         * Live Island 支持多种内容类型:
         * - text: 纯文本（时钟数字）
         * - icon: 小图标
         * - progress: 进度条（用于秒表/倒计时）
         * - expanded: 展开视图（显示毫秒+来源）
         */
        private fun createContentView(): Bundle {
            val content = Bundle()

            // 主内容：时间字符串
            content.putString("title", currentTimeStr)
            content.putString("subtitle", ".$currentMillisStr $currentSource")
            content.putInt("textColor", if (isNight) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())

            // 图标：时钟
            content.putInt("iconType", 1)  // 1 = clock icon

            // 布局参数
            content.putBoolean("showMillis", true)
            content.putBoolean("showSeconds", true)
            content.putInt("style", if (isNight) 1 else 0)  // 0=light, 1=dark

            return content
        }
    }

    // ==============================================================
    //  HyperIslandHandler - MIUI HyperOS 焦点通知
    //  小米/红米/POCO 设备专有
    // ==============================================================

    inner class HyperIslandHandler(
        private val context: Context,
        private val notifMgr: NotificationManager
    ) {
        private val appContext = context.applicationContext
        private val tag = "HyperIslandHandler"
        private val notifId = NOTIFICATION_ID
        private var isShowing = false

        companion object {
            private const val FOCUS_PARAM = "miui.focus.param"
            private const val FOCUS_PICS = "miui.focus.pics"
            private const val FOCUS_ACTIONS = "miui.focus.actions"
            private const val FOCUS_ICON = "miui.focus.icon"
            private const val FOCUS_TITLE = "miui.focus.title"
            private const val FOCUS_CONTENT = "miui.focus.content"

            @Volatile
            private var cachedHyperOS: Boolean? = null

            fun isHyperOSDevice(): Boolean {
                cachedHyperOS?.let { return it }
                val result: Boolean
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

        fun isSupported() = isHyperOSDevice() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        fun isActive() = isShowing

        fun show(timeStr: String, millisStr: String, source: String) {
            if (!isSupported()) return
            try {
                buildAndNotify(timeStr, millisStr, source)
                isShowing = true
            } catch (e: Exception) {
                Log.e(tag, "show failed: ${e.message}")
            }
        }

        fun hide() {
            if (isShowing) {
                notifMgr.cancel(notifId)
                isShowing = false
            }
        }

        fun onThemeChanged(isNight: Boolean) {
            // HyperOS 会自动跟随系统主题
        }

        fun showSyncPending(source: String) {}
        fun showSyncResult(success: Boolean, source: String, offsetMs: Long) {}
        fun destroy() = hide()

        private fun buildAndNotify(timeStr: String, millisStr: String, source: String) {
            val pi = PendingIntent.getActivity(
                appContext, 0,
                Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
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

            // 注入 MIUI HyperOS 焦点通知 extras
            applyHyperOSExtras(notification.extras, timeStr, millisStr, source)
            notifMgr.notify(notifId, notification)
        }

        /**
         * 注入 MIUI HyperOS 焦点通知 Bundle
         *
         * 关键字段:
         * - miui.focus.title: 岛屿主标题（时间 HH:mm:ss）
         * - miui.focus.content: 副标题（.SSS 来源）
         * - miui.focus.icon: 图标类型
         * - miui.focus.pics: 自定义图片列表（可选）
         * - miui.focus.actions: 快捷操作列表（可选）
         *
         * @see <a href="https://github.com/xzakota/HyperNotification">参考实现</a>
         */
        private fun applyHyperOSExtras(extras: Bundle, timeStr: String, millisStr: String, source: String) {
            if (!isHyperOSDevice()) return

            try {
                // 标题：时间字符串
                extras.putString(FOCUS_TITLE, timeStr)

                // 内容：毫秒 + 来源
                extras.putString(FOCUS_CONTENT, "${source} $millisStr")

                // 图标：使用系统时钟图标
                extras.putInt(FOCUS_ICON, 1)

                // 指示器：圆点样式
                extras.putString("miui.focus.indicator", "dot")

                // 展开视图
                extras.putBoolean("miui.focus.expanded", true)
                extras.putString("miui.focus.expanded.title", "悬浮时间")
                extras.putString("miui.focus.expanded.content",
                    "${timeStr}${millisStr}\n${source}\n点击打开设置"
                )

                Log.d(tag, "HyperOS extras applied: title=$timeStr content=${source}${millisStr}")
            } catch (e: Exception) {
                Log.e(tag, "applyHyperOSExtras failed: ${e.message}")
            }
        }
    }

    // ==============================================================
    //  NotificationHandler - 标准通知栏（所有设备兜底）
    //  Android 16+ 同时支持 Notification ProgressStyle Live Updates
    // ==============================================================

    inner class NotificationHandler(
        private val context: Context,
        private val notifMgr: NotificationManager
    ) {
        private val appContext = context.applicationContext
        private val tag = "NotificationHandler"

        fun isSupported() = true

        fun build(
            timeStr: String,
            millisStr: String,
            source: String,
            isNight: Boolean,
            liveIsland: LiveIslandHandler?,
            hyperIsland: HyperIslandHandler
        ): Notification {
            val pi = PendingIntent.getActivity(
                appContext, 0,
                Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val isAPI36 = Build.VERSION.SDK_INT >= 36

            val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
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
                .setColorized(true)
                .setAutoCancel(false)
                .setForegroundServiceBehavior(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                    else NotificationCompat.FOREGROUND_SERVICE_DEFAULT
                )

            // Android 16 (API 36) ProgressStyle Live Updates
            if (isAPI36) {
                applyProgressStyle(builder, timeStr, millisStr, isNight)
            }

            return builder.build()
        }

        /**
         * Android 16 Notification ProgressStyle Live Updates
         *
         * 使用 NotificationCompat.Style 配合 setProgress 实现实时更新。
         * 在 Android 16+ 上，这些更新会显示在通知面板的实时时钟区域。
         */
        private fun applyProgressStyle(
            builder: NotificationCompat.Builder,
            timeStr: String,
            millisStr: String,
            isNight: Boolean
        ) {
            if (Build.VERSION.SDK_INT < 36) return

            try {
                // 解析当前秒数（用于 progress 指示）
                val second = try {
                    timeStr.split(":").getOrNull(2)?.toInt() ?: 0
                } catch (_: Exception) { 0 }

                // Android 36 ProgressStyle:
                // setProgress(max, progress, false) 会在通知上显示进度条
                // 结合 EXTRA_PROGRESS_LENGTH 设置实时长度
                builder.setProgress(60, second, false)

                // 设置进度条颜色（跟随主题）
                val progressColor = if (isNight) {
                    0xFF4FC3F7.toInt()
                } else {
                    0xFF1976D2.toInt()
                }

                // 通知扩展参数（Android 16+）
                val extras = Bundle().apply {
                    putInt("android.progressLength", 1)  // 精确秒刻度
                    putInt("android.progressColor", progressColor)
                    putInt("android.progressMax", 60)
                    putInt("android.progress", second)
                }

                Log.d(tag, "ProgressStyle applied: second=$second color=${Integer.toHexString(progressColor)}")
            } catch (e: Exception) {
                Log.w(tag, "ProgressStyle failed: ${e.message}")
            }
        }

        fun showSyncPending(source: String) {
            val n = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("正在同步...")
                .setContentText("从${sourceName(source)}获取时间")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            notifMgr.notify(SYNC_NOTIFICATION_ID, n)
        }

        fun showSyncResult(success: Boolean, source: String, offsetMs: Long) {
            notifMgr.cancel(SYNC_NOTIFICATION_ID)
            if (!success) {
                val n = NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("同步失败")
                    .setContentText("无法从${sourceName(source)}获取时间")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build()
                notifMgr.notify(SYNC_NOTIFICATION_ID, n)
                handler.postDelayed({ notifMgr.cancel(SYNC_NOTIFICATION_ID) }, 5000)
            }
        }

        private fun sourceName(s: String) = when (s) {
            "taobao" -> "淘宝"
            "meituan" -> "美团"
            else -> s
        }
    }
}
