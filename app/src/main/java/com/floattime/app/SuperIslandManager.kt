package com.floattime.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * 超级岛管理器 (小米 HyperOS 焦点通知)
 *
 * 参考 HyperNotification (https://github.com/xzakota/HyperNotification) 的实现方案，
 * 通过 Notification extras 注入小米焦点通知数据，实现超级岛显示。
 */
class SuperIslandManager(context: Context) {

    companion object {
        private const val TAG = "SuperIslandManager"

        // 小米焦点通知 Bundle Keys
        private const val FOCUS_PARAM = "miui.focus.param"
        private const val FOCUS_PICS = "miui.focus.pics"
        private const val FOCUS_ACTIONS = "miui.focus.actions"

        // 通知渠道 (与 LiveUpdateManager 统一)
        private const val CHANNEL_ID = "float_time_live_updates"
        private const val NOTIFICATION_ID = 20240320

        // 缓存: HyperOS 检测只执行一次
        @Volatile
        private var cachedIsHyperOS: Boolean? = null

        @Synchronized
        private fun detectHyperOS(): Boolean {
            cachedIsHyperOS?.let { return it }

            try {
                val clazz = Class.forName("android.os.SystemProperties")
                val prop = clazz.getMethod("get", String::class.java)
                    .invoke(null, "ro.mi.os.version.incremental") as? String
                if (!prop.isNullOrEmpty()) {
                    Log.d(TAG, "HyperOS version: $prop")
                    cachedIsHyperOS = true
                    return true
                }
            } catch (_: Exception) {}

            val manufacturer = Build.MANUFACTURER.lowercase()
            val result = manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")
            cachedIsHyperOS = result
            return result
        }
    }

    private val appContext = context.applicationContext
    private val notifMgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    private val isHyperOS = detectHyperOS()

    init {
        Log.d(TAG, "SuperIslandManager initialized | SDK=${Build.VERSION.SDK_INT}" +
                " | Device=${Build.MANUFACTURER} ${Build.MODEL}" +
                " | HyperOS=$isHyperOS")
    }

    // =============================================
    //  公共 API
    // =============================================

    fun isHyperOS(): Boolean = isHyperOS

    /** 是否支持超级岛 (需要 HyperOS + Android 12+) */
    fun isSupported(): Boolean = isHyperOS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 将小米焦点通知 extras 注入到通知中 */
    fun applyFocusExtras(notification: Notification, timeStr: String, millisStr: String, source: String) {
        if (!isSupported()) return
        try {
            val focusExtras = buildFocusExtrasV2(timeStr, millisStr, source)
            notification.extras.putAll(focusExtras)
        } catch (e: Exception) {
            Log.e(TAG, "applyFocusExtras failed: ${e.message}")
        }
    }

    /** 显示独立的超级岛通知 */
    fun show(time: String, millis: String, source: String) {
        if (!isSupported()) return
        try {
            buildNotification(time, millis, source)?.let {
                notifMgr.notify(NOTIFICATION_ID, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "show failed: ${e.message}")
        }
    }

    /** 更新超级岛通知 */
    fun update(time: String, millis: String, source: String) = show(time, millis, source)

    /** 隐藏超级岛通知 */
    fun hide() { notifMgr.cancel(NOTIFICATION_ID) }
    fun destroy() = hide()

    // =============================================
    //  构建小米焦点通知数据 (V2 格式)
    // =============================================

    private fun buildFocusExtrasV2(timeStr: String, millisStr: String, source: String): Bundle {
        val extras = Bundle()
        try {
            val template = JSONObject().apply {
                put("ticker", timeStr + millisStr)
                put("enableFloat", true)

                put("baseInfo", JSONObject().apply {
                    put("type", 1) // INFO_TYPE_TEXT
                    put("title", "校准时间 $timeStr")
                    put("content", "$source | $millisStr")
                })

                put("hintInfo", JSONObject().apply {
                    put("type", 1)
                    put("title", "查看详情")
                    put("content", "点击打开悬浮时间")
                })
            }
            extras.putString(FOCUS_PARAM, template.toString())
        } catch (e: Exception) {
            Log.e(TAG, "buildFocusExtrasV2 failed: ${e.message}")
        }
        return extras
    }

    private fun buildNotification(time: String, millis: String, source: String): Notification? {
        val pi = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(time + millis)
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

        applyFocusExtras(notification, time, millis, source)
        return notification
    }
}
