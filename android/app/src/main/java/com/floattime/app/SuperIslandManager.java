package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 超级岛管理器
 * 
 * 使用标准 NotificationCompat API
 * 支持 Android 12+ 的气泡通知
 * 
 * 注意：真正的超级岛需要 HyperOS 系统支持
 */
public class SuperIslandManager {

    private static final String TAG = "SuperIslandManager";
    
    // 通知渠道
    private static final String CHANNEL_ID = "float_time_super_island";
    private static final String CHANNEL_NAME = "悬浮时间超级岛";
    private static final int NOTIFICATION_ID = 20240322;

    private final Context mContext;
    private final NotificationManager mNotifMgr;

    private boolean mIsEnabled = false;

    public SuperIslandManager(Context context) {
        mContext = context.getApplicationContext();
        mNotifMgr = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        
        Log.d(TAG, "SuperIslandManager initialized | SDK: " + Build.VERSION.SDK_INT);
        Log.d(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL);
    }

    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("悬浮时间的超级岛显示");
            channel.setShowBadge(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            
            mNotifMgr.createNotificationChannel(channel);
        }
    }

    /**
     * 是否支持超级岛
     */
    public boolean isSupported() {
        // Android 12+ 支持
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * 是否已启用
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * 启用超级岛
     */
    public void enable(OnResultListener listener) {
        mIsEnabled = true;
        if (listener != null) {
            listener.onSuccess("超级岛已启用");
        }
    }

    /**
     * 显示通知
     */
    public void show(String time, String millis, String source) {
        try {
            Notification notification = buildNotification(time, millis, source);
            if (notification != null) {
                mNotifMgr.notify(NOTIFICATION_ID, notification);
                Log.d(TAG, "Notification shown: " + time + millis);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to show notification: " + e.getMessage());
        }
    }

    /**
     * 更新通知
     */
    public void update(String time, String millis, String source) {
        show(time, millis, source);
    }

    /**
     * 隐藏通知
     */
    public void hide() {
        mNotifMgr.cancel(NOTIFICATION_ID);
        Log.d(TAG, "Notification hidden");
    }

    /**
     * 构建通知
     */
    private Notification buildNotification(String time, String millis, String source) {
        Intent mainIntent = new Intent(mContext, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            mContext, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(time + millis)
            .setContentText(source)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // Android 12+ 前台服务行为
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }

    /**
     * 销毁
     */
    public void destroy() {
        hide();
    }

    /**
     * 结果监听器
     */
    public interface OnResultListener {
        void onSuccess(String message);
        void onFailure(String error);
    }
}
