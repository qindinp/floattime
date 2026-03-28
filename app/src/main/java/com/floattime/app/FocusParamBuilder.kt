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
 * 小米焦点通知参数构建器 (V3 格式)
 *
 * 参考 Capsulyric 的 FocusNotification.buildV3 DSL 实现，
 * 用 Kotlin 构建与 HyperNotification V3 等价的 JSON 结构。
 *
 * 生成的 JSON 写入 notification.extras 的 "miui.focus.param" key，
 * HyperOS 系统会读取此 key 并在超级岛上展示内容。
 *
 * 模板类型：
 * - 带 progressInfo → Template 21 (IM + Progress 2)
 * - 带 actions → Template 7 (Media Controls)
 */
class FocusParamBuilder(private val context: Context) {

    companion object {
        private const val TAG = "FocusParamBuilder"

        // 小米焦点通知 Bundle Keys
        const val KEY_FOCUS_PARAM = "miui.focus.param"
        const val KEY_FOCUS_PICS = "miui.focus.pics"
        const val KEY_FOCUS_ACTIONS = "miui.focus.actions"

        // 通知渠道
        const val CHANNEL_ID = "float_time_live_updates"

        private const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
    }

    private val appContext = context.applicationContext

    // V3 参数
    private var mBusiness = "float_time"
    private var mEnableFloat = false
    private var mUpdatable = true
    private var mIslandFirstFloat = false

    // AOD (息屏显示)
    private var mAodTitle = ""

    // ChatInfo (通知栏聊天式信息)
    private var mChatTitle = ""
    private var mChatContent = ""
    private var mChatAppIconPkg = ""

    // Island (超级岛区域)
    private var mIslandTitle = ""
    private var mIslandTextTitle = ""
    private var mShowHighlightColor = false
    private var mHighlightColor = "#757575"

    // Small Island (胶囊形态)
    private var mProgress = 0
    private var mProgressColor = "#757575"
    private var mProgressColorUnreach = "#333333"

    // Share Data (拖拽分享)
    private var mShareEnabled = true
    private var mShareTitle = ""
    private var mShareContent = ""

    // Ticker (状态栏摘要)
    private var mTicker = ""

    // =============================================
    //  链式设置方法
    // =============================================

    fun setBusiness(business: String) = apply { mBusiness = business }

    fun setEnableFloat(enable: Boolean) = apply { mEnableFloat = enable }

    fun setUpdatable(updatable: Boolean) = apply { mUpdatable = updatable }

    fun setIslandFirstFloat(first: Boolean) = apply { mIslandFirstFloat = first }

    fun setAodTitle(title: String) = apply { mAodTitle = title }

    fun setTicker(ticker: String) = apply { mTicker = ticker }

    // ChatInfo
    fun setChatTitle(title: String) = apply { mChatTitle = title }

    fun setChatContent(content: String) = apply { mChatContent = content }

    fun setChatAppIconPkg(pkg: String) = apply { mChatAppIconPkg = pkg }

    // Island
    fun setIslandTitle(title: String) = apply { mIslandTitle = title }

    fun setIslandTextTitle(title: String) = apply { mIslandTextTitle = title }

    fun setShowHighlightColor(show: Boolean) = apply { mShowHighlightColor = show }

    fun setHighlightColor(color: String) = apply { mHighlightColor = color }

    // Progress
    fun setProgress(progress: Int) = apply { mProgress = progress }

    fun setProgressColor(color: String) = apply { mProgressColor = color }

    fun setProgressColorUnreach(color: String) = apply { mProgressColorUnreach = color }

    // Share
    fun setShareEnabled(enabled: Boolean) = apply { mShareEnabled = enabled }

    fun setShareTitle(title: String) = apply { mShareTitle = title }

    fun setShareContent(content: String) = apply { mShareContent = content }

    // =============================================
    //  构建方法
    // =============================================

    /**
     * 构建 miui.focus.param JSON 字符串
     */
    fun buildParamJson(): String? {
        return try {
            val root = JSONObject()

            // 顶层属性
            root.put("business", mBusiness)
            root.put("enableFloat", mEnableFloat)
            root.put("updatable", mUpdatable)
            root.put("islandFirstFloat", mIslandFirstFloat)
            root.put("aodTitle", mAodTitle)
            root.put("ticker", mTicker)

            // chatInfo — 通知栏展示
            val chatInfo = JSONObject().apply {
                put("title", mChatTitle)
                put("content", mChatContent)
                if (mChatAppIconPkg.isNotEmpty()) {
                    put("appIconPkg", mChatAppIconPkg)
                }
            }
            root.put("chatInfo", chatInfo)

            // island — 超级岛
            val island = JSONObject().apply {
                put("islandProperty", 1)
                if (mShowHighlightColor) {
                    put("highlightColor", mHighlightColor)
                }

                // bigIslandArea — 展开态
                val bigIslandArea = JSONObject()

                val imageTextInfoLeft = JSONObject().apply {
                    put("type", 1)
                    val textInfo = JSONObject().apply {
                        put("title", mIslandTitle)
                        put("showHighlightColor", mShowHighlightColor)
                    }
                    put("textInfo", textInfo)
                }
                bigIslandArea.put("imageTextInfoLeft", imageTextInfoLeft)

                // textInfo — 右侧滚动文本
                val textInfo = JSONObject().apply {
                    put("title", mIslandTextTitle)
                    put("showHighlightColor", mShowHighlightColor)
                    put("narrowFont", false)
                }
                bigIslandArea.put("textInfo", textInfo)

                put("bigIslandArea", bigIslandArea)

                // smallIslandArea — 胶囊形态
                val smallIslandArea = JSONObject()
                val combinePicInfo = JSONObject()
                val progressInfo = JSONObject().apply {
                    put("progress", mProgress)
                    put("colorReach", mProgressColor)
                    put("colorUnReach", mProgressColorUnreach)
                }
                combinePicInfo.put("progressInfo", progressInfo)
                smallIslandArea.put("combinePicInfo", combinePicInfo)
                put("smallIslandArea", smallIslandArea)

                // shareData — 拖拽分享
                if (mShareEnabled) {
                    val shareData = JSONObject().apply {
                        put("title", mShareTitle)
                        put("content", mShareContent)
                    }
                    put("shareData", shareData)
                }
            }

            root.put("island", island)

            root.toString()

        } catch (e: Exception) {
            Log.e(TAG, "buildParamJson failed: ${e.message}")
            null
        }
    }

    /**
     * 构建完整的焦点通知 Bundle
     * 包含 miui.focus.param JSON
     */
    fun buildExtras(): Bundle {
        val extras = Bundle()
        val json = buildParamJson()
        if (json != null) {
            extras.putString(KEY_FOCUS_PARAM, json)
        }
        return extras
    }

    /**
     * 构建带有焦点通知的完整 Notification
     *
     * @param timeStr    时间字符串 (HH:mm:ss)
     * @param millisStr  毫秒字符串 (.xxx)
     * @param source     时间源名称
     * @param isNight    是否夜间模式
     */
    fun buildNotification(timeStr: String, millisStr: String, source: String, isNight: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(timeStr + millisStr)
            .setContentText(source)
            .setContentIntent(pendingIntent)
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

        // 注入焦点通知 extras
        val focusExtras = buildExtras()
        notification.extras.putAll(focusExtras)

        return notification
    }
}
