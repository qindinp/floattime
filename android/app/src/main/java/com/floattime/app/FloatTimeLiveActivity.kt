package com.floattime.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person.Builder as PersonBuilder

/**
 * FloatTime Live Activity Service
 * 
 * 使用 Android 12+ 的 Bubbles API 在通知栏创建类似超级岛的效果
 * Bubbles 会以浮动圆角卡片的形式显示在屏幕上
 * 
 * 注意：需要在 AndroidManifest.xml 中注册 BubbleActivity
 */
class FloatTimeLiveActivity : Service() {

    companion object {
        private const val TAG = "FloatTimeLiveActivity"
        
        // 通知渠道 ID
        private const val CHANNEL_ID = "float_time_live_activity"
        private const val CHANNEL_NAME = "实时活动"
        
        // 通知 ID
        private const val NOTIFICATION_ID = 20240321
        
        // Bubble Activity 的 Intent
        private var sBubbleIntent: Intent? = null
        private var sBubbleIcon: Icon? = null
        private var sBubbleTitle: String = "悬浮时间"
        
        /**
         * 启动 Live Activity
         */
        fun start(context: Context, timeStr: String, millisStr: String, sourceName: String) {
            val intent = Intent(context, FloatTimeLiveActivity::class.java).apply {
                action = "START"
                putExtra("time", timeStr)
                putExtra("millis", millisStr)
                putExtra("source", sourceName)
            }
            context.startService(intent)
        }
        
        /**
         * 更新 Live Activity
         */
        fun update(context: Context, timeStr: String, millisStr: String, sourceName: String) {
            val intent = Intent(context, FloatTimeLiveActivity::class.java).apply {
                action = "UPDATE"
                putExtra("time", timeStr)
                putExtra("millis", millisStr)
                putExtra("source", sourceName)
            }
            context.startService(intent)
        }
        
        /**
         * 停止 Live Activity
         */
        fun stop(context: Context) {
            val intent = Intent(context, FloatTimeLiveActivity::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
        }
        
        /**
         * 设置 Bubble 元数据
         */
        fun setBubbleMetadata(title: String, iconResId: Int) {
            sBubbleTitle = title
        }
    }
    
    private var mNotificationManager: NotificationManager? = null
    private var mHandler: Handler? = null
    private var mCurrentTime: String = "--:--:--"
    private var mCurrentMillis: String = ".000"
    private var mCurrentSource: String = "悬浮时间"
    
    override fun onCreate() {
        super.onCreate()
        mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mHandler = Handler(Looper.getMainLooper())
        
        createNotificationChannel()
        Log.d(TAG, "FloatTimeLiveActivity started")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                mCurrentTime = intent.getStringExtra("time") ?: "--:--:--"
                mCurrentMillis = intent.getStringExtra("millis") ?: ".000"
                mCurrentSource = intent.getStringExtra("source") ?: "悬浮时间"
                showLiveActivity()
            }
            "UPDATE" -> {
                mCurrentTime = intent.getStringExtra("time") ?: mCurrentTime
                mCurrentMillis = intent.getStringExtra("millis") ?: mCurrentMillis
                mCurrentSource = intent.getStringExtra("source") ?: mCurrentSource
                updateLiveActivity()
            }
            "STOP" -> {
                stopLiveActivity()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationManager.ChannelInfo.Builder(
                CHANNEL_ID,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setName(CHANNEL_NAME)
                setDescription("悬浮时间的实时活动显示")
                setShowBadge(true)
                setAllowBubbles(true)  // ✅ 启用气泡通知
                setLightsEnabled(true)
                setLightColor(0xFFFF6B35.toInt())
                setVibrationEnabled(true)
                setBypassDnd(true)  // ✅ 绕过勿扰模式
            }.build()
            
            mNotificationManager?.createNotificationChannel(channel)
        }
    }
    
    /**
     * 显示 Live Activity (Bubble)
     */
    private fun showLiveActivity() {
        try {
            // 创建指向 MainActivity 的 Intent
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // 创建 BubbleMetadata
            val bubbleIntent = PendingIntent.getActivity(
                this, 1, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            // ✅ 创建头像/图标
            val shortcutIcon = Icon.createWithResource(this, android.R.drawable.ic_lock_idle_alarm)
            
            // ✅ 创建 BubbleMetadata.Builder
            val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
                shortcutIcon,
                bubbleIntent
            ).apply {
                // 设置 Bubble 展开时的高度
                setDesiredHeight(300)
                // 设置为自动展开
                setAutoExpandBubble(true)
                // 不在通知栏显示额外通知
                setSuppressNotification(false)
                // 设置棉绒文字
                setExpandedTitle(mCurrentSource)
                setExpandedText("$mCurrentTime$mCurrentMillis")
            }.build()
            
            // ✅ 创建一个 Person 作为 Bubble 的联系人信息
            val person = Person.Builder().apply {
                setName(mCurrentSource)
                setIcon(shortcutIcon)
                setImportant(true)
            }.build()
            
            // ✅ 创建 MessagingStyle 来支持实时更新
            val messagingStyle = NotificationCompat.MessagingStyle(person).apply {
                // 添加初始消息
                addMessage(
                    "$mCurrentTime$mCurrentMillis",
                    System.currentTimeMillis(),
                    person
                )
                // 允许对话
                setConversationTitle(mCurrentSource)
                setGroupConversation(false)
            }
            
            // ✅ 构建通知
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(mCurrentSource)
                .setContentText("$mCurrentTime$mCurrentMillis")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setBubbleMetadata(bubbleMetadata)
                .setStyle(messagingStyle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
            
            mNotificationManager?.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Live Activity (Bubble) shown: $mCurrentTime$mCurrentMillis")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Live Activity: ${e.message}")
        }
    }
    
    /**
     * 更新 Live Activity
     */
    private fun updateLiveActivity() {
        showLiveActivity()
    }
    
    /**
     * 停止 Live Activity
     */
    private fun stopLiveActivity() {
        mNotificationManager?.cancel(NOTIFICATION_ID)
        Log.d(TAG, "Live Activity stopped")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FloatTimeLiveActivity destroyed")
    }
}
