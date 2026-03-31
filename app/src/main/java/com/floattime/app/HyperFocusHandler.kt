package com.floattime.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.xzakota.hyper.notification.focus.FocusNotification

/**
 * HyperFocusHandler - 小米超级岛 V3 DSL 渲染器
 *
 * 使用 HyperNotification 库的 FocusNotification.buildV3 DSL，
 * 替代手拼 JSONObject，确保与 HyperOS 焦点通知协议完全兼容。
 *
 * 功能：
 * - 大岛区域（展开态）：显示时间标题 + 源信息
 * - 小岛区域（胶囊态）：显示进度环 + 时间文本
 * - 分享区域：拖拽可分享当前时间
 * - 通知栏聊天式：标准焦点通知形态
 *
 * @see <a href="https://github.com/xzakota/HyperNotification">HyperNotification</a>
 */
class HyperFocusHandler(private val context: Context) {

    companion object {
        private const val TAG = "HyperFocusHandler"

        // 通知渠道 (与 IslandManager 统一)
        const val CHANNEL_ID = "float_time_live_updates"
        private const val NOTIFICATION_ID = 20240320

        // 进度环参数
        private const val PROGRESS_MAX = 60

        // 颜色
        private const val COLOR_PROGRESS_REACH = "#1976D2"
        private const val COLOR_PROGRESS_UNREACH = "#333333"
        private const val COLOR_PROGRESS_NIGHT = "#4FC3F7"

        // 缓存
        @Volatile
        private var cachedIsHyperOS: Boolean? = null
    }

    private val appContext = context.applicationContext
    private val notifMgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Volatile
    private var isShowing = false
    private var lastUpdateTime = 0L
    private val throttleIntervalMs = 500L

    private val cachedContentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    init {
        ensureChannel()
        Log.d(TAG, "HyperFocusHandler initialized | SDK=${Build.VERSION.SDK_INT}" +
                " | Device=${Build.MANUFACTURER} ${Build.MODEL}" +
                " | HyperOS=${isHyperOS()}")
    }

    // =============================================
    //  公共 API
    // =============================================

    fun isHyperOS(): Boolean = detectHyperOS()

    fun isSupported(): Boolean = isHyperOS() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun isActive(): Boolean = isShowing

    /**
     * 显示超级岛通知
     *
     * @param timeStr   时间字符串 (HH:mm:ss)
     * @param millisStr 毫秒字符串 (.xxx)
     * @param source    时间源名称 (taobao/meituan/local)
     * @param isNight   是否夜间模式
     */
    fun show(timeStr: String, millisStr: String, source: String, isNight: Boolean) {
        if (!isSupported()) return

        val now = System.currentTimeMillis()
        if (isShowing && now - lastUpdateTime < throttleIntervalMs) return
        lastUpdateTime = now

        try {
            val notification = buildNotification(timeStr, millisStr, source, isNight)
            notifMgr.notify(NOTIFICATION_ID, notification)
            isShowing = true
            Log.d(TAG, "Shown: $timeStr$millisStr | $source")
        } catch (e: Exception) {
            Log.e(TAG, "show failed: ${e.message}")
        }
    }

    fun update(timeStr: String, millisStr: String, source: String, isNight: Boolean) =
        show(timeStr, millisStr, source, isNight)

    fun hide() {
        if (isShowing) {
            notifMgr.cancel(NOTIFICATION_ID)
            isShowing = false
        }
    }

    fun destroy() = hide()

    /**
     * 将焦点通知 extras 注入到已有 Notification 中
     * 用于前台服务 startForeground 场景
     */
    fun applyFocusExtras(notification: Notification, timeStr: String, millisStr: String, source: String, isNight: Boolean) {
        if (!isSupported()) return
        try {
            val second = parseSecond(timeStr)
            val progressColor = if (isNight) COLOR_PROGRESS_NIGHT else COLOR_PROGRESS_REACH
            val displayTime = timeStr + millisStr
            val displaySource = sourceDisplayName(source)

            val extras = FocusNotification.buildV3 {
                business = "float_time"
                enableFloat = false
                updatable = true
                islandFirstFloat = false
                aodTitle = displayTime.take(20)

                chatInfo {
                    title = displayTime
                    content = displaySource
                    appIconPkg = appContext.packageName
                }

                island {
                    islandProperty = 1

                    bigIslandArea {
                        imageTextInfoLeft {
                            type = 1
                            textInfo {
                                title = "校准时间"
                                showHighlightColor = false
                            }
                        }
                        imageTextInfoRight {
                            type = 2
                            textInfo {
                                title = displayTime
                                showHighlightColor = false
                            }
                        }
                    }

                    textInfo {
                        title = "$displaySource | $millisStr"
                        showHighlightColor = false
                        narrowFont = false
                    }

                    shareData {
                        title = "悬浮时间"
                        content = displayTime
                        shareContent = "$displayTime ($displaySource)"
                    }
                }

                smallIslandArea {
                    combinePicInfo {
                        progressInfo {
                            progress = second
                            colorReach = progressColor
                            colorUnReach = COLOR_PROGRESS_UNREACH
                        }
                    }
                }
            }

            notification.extras.putAll(extras)
        } catch (e: Exception) {
            Log.e(TAG, "applyFocusExtras failed: ${e.message}")
        }
    }

    // =============================================
    //  构建完整通知
    // =============================================

    private fun buildNotification(timeStr: String, millisStr: String, source: String, isNight: Boolean): Notification {
        val displayTime = timeStr + millisStr

        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(displayTime)
            .setContentText(sourceDisplayName(source))
            .setContentIntent(cachedContentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        // 注入焦点通知 extras
        applyFocusExtras(notification, timeStr, millisStr, source, isNight)

        return notification
    }

    // =============================================
    //  工具方法
    // =============================================

    private fun parseSecond(timeStr: String): Int =
        try { timeStr.split(":").getOrNull(2)?.toInt() ?: 0 }
        catch (_: Exception) { 0 }

    private fun sourceDisplayName(source: String): String = when (source) {
        "taobao" -> "淘宝时间"
        "meituan" -> "美团时间"
        "local" -> "本地时间"
        else -> source
    }

    @Synchronized
    private fun detectHyperOS(): Boolean {
        cachedIsHyperOS?.let { return it }
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val prop = clazz.getMethod("get", String::class.java)
                .invoke(null, "ro.mi.os.version.incremental") as? String
            if (!prop.isNullOrEmpty()) {
                cachedIsHyperOS = true
                return true
            }
        } catch (_: Exception) {}
        val m = Build.MANUFACTURER.lowercase()
        val result = m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
        cachedIsHyperOS = result
        return result
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notifMgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "悬浮时间", NotificationManager.IMPORTANCE_LOW).apply {
            description = "实时显示校准时间"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            setBypassDnd(true)
        }
        notifMgr.createNotificationChannel(ch)
    }
}
