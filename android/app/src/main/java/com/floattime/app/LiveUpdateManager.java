package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

/**
 * Android 16 Live Updates 管理器
 * 支持时间同步状态、秒表进度等实时通知展示
 * 
 * Android 16 Live Updates 特性:
 * - 实时更新通知内容，无需重新发送
 * - 支持动画进度条
 * - 支持实时计时器显示
 */
public class LiveUpdateManager {

    private static final String TAG = "LiveUpdateManager";
    
    // 通知渠道
    private static final String CHANNEL_ID_LIVE_UPDATE = "float_time_live_update";
    private static final String CHANNEL_NAME_LIVE_UPDATE = "实时活动";
    private static final String CHANNEL_DESC_LIVE_UPDATE = "显示时间同步、秒表等实时状态";
    
    // 通知 ID
    public static final int NOTIFICATION_ID_TIME_SYNC = 3001;
    public static final int NOTIFICATION_ID_STOPWATCH = 3002;
    public static final int NOTIFICATION_ID_COUNTDOWN = 3003;
    
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final Handler mHandler;
    
    // 秒表计时器
    private Runnable mStopwatchRunnable;
    private long mStopwatchStartTime;
    private boolean mIsStopwatchRunning = false;
    
    public LiveUpdateManager(Context context) {
        mContext = context.getApplicationContext();
        mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
        
        createNotificationChannel();
    }
    
    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID_LIVE_UPDATE,
                CHANNEL_NAME_LIVE_UPDATE,
                NotificationManager.IMPORTANCE_MAX  // ✅ 改为 MAX，让小米超级岛识别
            );
            channel.setDescription(CHANNEL_DESC_LIVE_UPDATE);
            channel.setShowBadge(true);  // ✅ 改为 true，让小米超级岛识别
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            
            // ✅ 添加声音和振动，让小米超级岛识别
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 250, 250, 250});
            
            // ✅ 添加灯光效果
            channel.enableLights(true);
            channel.setLightColor(0xFFFF6B35);  // 橙色
            
            // Android 16+ 启用 Live Updates
            if (Build.VERSION.SDK_INT >= 36) {
                channel.setAllowBubbles(true);
            }
            
            if (mNotificationManager != null) {
                mNotificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * 检查是否支持 Live Updates (Android 16+)
     */
    public boolean isLiveUpdateSupported() {
        // Android 16 (API 36) 及以上支持 Live Updates
        return Build.VERSION.SDK_INT >= 36;
    }
    
    /**
     * 检查是否有权限发送 Live Updates
     */
    public boolean canPostLiveUpdates() {
        if (!isLiveUpdateSupported()) {
            return false;
        }
        
        if (mNotificationManager != null) {
            return mNotificationManager.areNotificationsEnabled();
        }
        
        return false;
    }
    
    // ==================== 时间同步 Live Update ====================
    
    /**
     * 显示时间同步中状态
     */
    public void showTimeSyncing(String source) {
        String sourceName = getSourceDisplayName(source);
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID_LIVE_UPDATE)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("时间同步中")
            .setContentText("正在从 " + sourceName + " 获取时间...")
            .setOngoing(true)
            .setProgress(0, 0, true)  // 不确定进度
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        
        // Android 16+ Live Updates 配置
        if (isLiveUpdateSupported()) {
            // 启用实时更新
            builder.setShowWhen(true);
            builder.setUsesChronometer(false);
        }
        
        notify(NOTIFICATION_ID_TIME_SYNC, builder.build());
    }
    
    /**
     * 显示时间同步成功
     */
    public void showTimeSyncSuccess(String source, long offsetMs) {
        String sourceName = getSourceDisplayName(source);
        String offsetText = offsetMs >= 0 ? "+" + offsetMs + "ms" : offsetMs + "ms";
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID_LIVE_UPDATE)
            .setSmallIcon(android.R.drawable.ic_input_get)
            .setContentTitle("时间已校准")
            .setContentText(sourceName + " | 偏差: " + offsetText)
            .setOngoing(false)
            .setProgress(100, 100, false)  // 完成进度
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true);
        
        // Android 16+ Live Updates 配置
        if (isLiveUpdateSupported()) {
            builder.setShowWhen(true);
        }
        
        notify(NOTIFICATION_ID_TIME_SYNC, builder.build());
        
        // 3秒后自动清除
        mHandler.postDelayed(() -> {
            cancel(NOTIFICATION_ID_TIME_SYNC);
        }, 3000);
    }
    
    /**
     * 显示时间同步失败
     */
    public void showTimeSyncFailed(String source) {
        String sourceName = getSourceDisplayName(source);
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID_LIVE_UPDATE)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("时间同步失败")
            .setContentText("无法连接 " + sourceName + "，已切换到本地时间")
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true);
        
        // Android 16+ Live Updates 配置
        if (isLiveUpdateSupported()) {
            builder.setShowWhen(true);
        }
        
        notify(NOTIFICATION_ID_TIME_SYNC, builder.build());
        
        // 5秒后自动清除
        mHandler.postDelayed(() -> {
            cancel(NOTIFICATION_ID_TIME_SYNC);
        }, 5000);
    }
    
    // ==================== 秒表 Live Update ====================
    
    /**
     * 开始秒表 Live Update
     */
    public void startStopwatch() {
        if (mIsStopwatchRunning) {
            return;
        }
        
        mIsStopwatchRunning = true;
        mStopwatchStartTime = System.currentTimeMillis();
        
        // 创建暂停/停止操作的 PendingIntent
        Intent pauseIntent = new Intent(mContext, FloatTimeService.class);
        pauseIntent.setAction("STOPWATCH_PAUSE");
        PendingIntent pausePendingIntent = PendingIntent.getService(
            mContext, 0, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent stopIntent = new Intent(mContext, FloatTimeService.class);
        stopIntent.setAction("STOPWATCH_STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(
            mContext, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // 启动定时更新
        mStopwatchRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mIsStopwatchRunning) {
                    return;
                }
                
                long elapsedMs = System.currentTimeMillis() - mStopwatchStartTime;
                updateStopwatchNotification(elapsedMs, pausePendingIntent, stopPendingIntent);
                
                // 每 100ms 更新一次
                mHandler.postDelayed(this, 100);
            }
        };
        
        mHandler.post(mStopwatchRunnable);
    }
    
    /**
     * 更新秒表通知 - 支持 Android 16 Live Updates
     */
    private void updateStopwatchNotification(long elapsedMs, PendingIntent pauseIntent, PendingIntent stopIntent) {
        long minutes = elapsedMs / 60000;
        long seconds = (elapsedMs % 60000) / 1000;
        long millis = (elapsedMs % 1000) / 10;
        
        String timeText = String.format("%02d:%02d.%02d", minutes, seconds, millis);
        int progress = (int) ((elapsedMs % 60000) / 600);  // 一分钟内的进度
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID_LIVE_UPDATE)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("秒表运行中")
            .setContentText(timeText)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_pause, "暂停", pauseIntent)
            .addAction(android.R.drawable.ic_delete, "停止", stopIntent);
        
        // Android 16+ Live Updates 配置
        if (isLiveUpdateSupported()) {
            // 启用实时更新和计时器
            builder.setShowWhen(true);
            builder.setUsesChronometer(true);
            builder.setChronometerCountDown(false);
            
            // 使用 BigTextStyle 显示更多信息
            builder.setStyle(new NotificationCompat.BigTextStyle()
                .bigText("已运行: " + timeText + "\n进度: " + progress + "%"));
        }
        
        notify(NOTIFICATION_ID_STOPWATCH, builder.build());
    }
    
    /**
     * 暂停秒表
     */
    public void pauseStopwatch() {
        mIsStopwatchRunning = false;
        if (mStopwatchRunnable != null) {
            mHandler.removeCallbacks(mStopwatchRunnable);
        }
        
        // 更新为暂停状态
        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID_LIVE_UPDATE)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("秒表已暂停")
            .setContentText("点击继续")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH);
        
        // Android 16+ Live Updates 配置
        if (isLiveUpdateSupported()) {
            builder.setShowWhen(true);
        }
        
        notify(NOTIFICATION_ID_STOPWATCH, builder.build());
    }
    
    /**
     * 停止秒表
     */
    public void stopStopwatch() {
        mIsStopwatchRunning = false;
        if (mStopwatchRunnable != null) {
            mHandler.removeCallbacks(mStopwatchRunnable);
        }
        
        cancel(NOTIFICATION_ID_STOPWATCH);
    }
    
    /**
     * 检查秒表是否正在运行
     */
    public boolean isStopwatchRunning() {
        return mIsStopwatchRunning;
    }
    
    // ==================== 工具方法 ====================
    
    private String getSourceDisplayName(String source) {
        if ("taobao".equals(source)) {
            return "淘宝时间";
        } else if ("meituan".equals(source)) {
            return "美团时间";
        } else {
            return "本地时间";
        }
    }
    
    private void notify(int id, Notification notification) {
        // ✅ 修复: 添加 null 检查
        if (mNotificationManager != null && notification != null) {
            mNotificationManager.notify(id, notification);
        }
    }
    
    private void cancel(int id) {
        if (mNotificationManager != null) {
            mNotificationManager.cancel(id);
        }
    }
    
    /**
     * 清除所有 Live Updates
     */
    public void clearAll() {
        stopStopwatch();
        cancel(NOTIFICATION_ID_TIME_SYNC);
        cancel(NOTIFICATION_ID_COUNTDOWN);
    }
}
