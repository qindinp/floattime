package com.floattime.app

import android.app.NotificationManager
import android.app.PendingIntent
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

/**
 * FloatTime Live Activity Service
 * 
 * 使用 Android 12+ 的 Bubbles API 在通知栏创建类似超级岛的效果
 * Bubbles 会以浮动圆角卡片的形式显示在屏幕上
 */
class FloatTimeLiveActivity : Service() {

    companion object {
        private const val TAG = "FloatTimeLiveActivity"
        
        // 通知渠道 ID
        private const val CHANNEL_ID = "float_time_live_activity"
        private const val CHANNEL_NAME = "实时活动"
        
        // 通知 ID
        private const val NOTIFICATION_ID = 20240321
        
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
    }
    
    private var mNotificationManager: NotificationManager? = null
    private var mCurrentTime: String = "--:--:--"
    private var mCurrentMillis: String = ".000"
    private var mCurrentSource: String = "悬浮时间"
    
    override fun onCreate() {
        super.onCreate()
        mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
                setAllowBubbles(true)
                setLightsEnabled(true)
                setLightColor(0xFFFF6B35.toInt())
                setVibrationEnabled(true)
                setBypassDnd(true)
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
            
            // Bubble Activity 的 PendingIntent
            val bubbleIntent = PendingIntent.getActivity(
                this, 1, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            // 创建图标
            val shortcutIcon = Icon.createWithResource(this, android.R.drawable.ic_lock_idle_alarm)
            
            // 构建 BubbleMetadata
            val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
                shortcutIcon,
                bubbleIntent
            ).apply {
                setDesiredHeight(300)
                setAutoExpandBubble(true)
                setSuppressNotification(false)
            }.build()
            
            // 构建通知
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(mCurrentSource)
                .setContentText("$mCurrentTime$mCurrentMillis")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setBubbleMetadata(bubbleMetadata)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
            
            mNotificationManager?.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Live Activity shown: $mCurrentTime$mCurrentMillis")
            
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
