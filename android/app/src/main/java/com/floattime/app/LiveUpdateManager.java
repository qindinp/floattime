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
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * LiveUpdateManager - 实时更新通知管理器
 * 
 * 使用稳定的 NotificationCompat API，支持:
 * - 时钟通知 (实时更新时间)
 * - 时间同步状态
 * - 秒表功能
 * 
 * 兼容所有 Android 版本 (API 24+)
 */
public class LiveUpdateManager {

    private static final String TAG = "LiveUpdateManager";

    private static final String CHANNEL_ID   = "float_time_live_updates";
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final String CHANNEL_DESC = "实时显示校准时间";

    public static final int ID_CLOCK = 20240320;  // 时钟通知 (与前台服务共用)
    public static final int ID_SYNC_STATUS = 20240321;  // 同步状态

    private final Context mContext;
    private final NotificationManager mNotifMgr;
    private final Handler mHandler;

    public LiveUpdateManager(Context context) {
        mContext = context.getApplicationContext();
        mNotifMgr = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
        createChannel();
        Log.d(TAG, "LiveUpdateManager initialized | API=" + Build.VERSION.SDK_INT);
    }

    // ================================================================
    //  渠道创建
    // ================================================================

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(CHANNEL_DESC);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.enableLights(false);
        ch.setSound(null, null);
        ch.setVibrationPattern(null);
        mNotifMgr.createNotificationChannel(ch);
    }

    // ================================================================
    //  公共方法
    // ================================================================

    /**
     * 检查是否支持 Live Updates
     * 所有 API 24+ 设备都支持
     */
    public boolean isSupported() {
        return true;  // 使用稳定的 NotificationCompat，所有设备都支持
    }

    /**
     * 创建时钟通知
     */
    public Notification createClockNotification(Context ctx,
                                               String timeStr, String millisStr,
                                               String source) {
        return createClockNotification(ctx, timeStr, millisStr, source, false);
    }

    /**
     * 创建时钟通知 (带主题)
     */
    public Notification createClockNotification(Context ctx,
                                               String timeStr, String millisStr,
                                               String source, boolean isNight) {
        PendingIntent pi = createMainPendingIntent(ctx);

        // 使用自定义布局显示详细信息
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(timeStr + millisStr)
                .setContentText(source)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // Android 12+ 灵动岛支持
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }

    /**
     * 更新时钟通知
     */
    public void updateClock(Context ctx, String timeStr, String millisStr, String source) {
        updateClock(ctx, timeStr, millisStr, source, false);
    }

    /**
     * 更新时钟通知 (带主题)
     */
    public void updateClock(Context ctx, String timeStr, String millisStr, String source, boolean isNight) {
        Notification notification = createClockNotification(ctx, timeStr, millisStr, source, isNight);
        mNotifMgr.notify(ID_CLOCK, notification);
    }

    /**
     * 显示同步中状态
     */
    public void showTimeSyncing(String source) {
        String title = "正在同步...";
        String text = "从" + getSourceName(source) + "获取时间";

        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();

        mNotifMgr.notify(ID_SYNC_STATUS, notification);
    }

    /**
     * 显示同步成功
     */
    public void showTimeSyncSuccess(String source, long offsetMs) {
        String title = "时间已同步";
        String text = String.format("%s | 偏移: %+.0fms", 
                getSourceName(source), (double) offsetMs);

        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();

        mNotifMgr.notify(ID_SYNC_STATUS, notification);

        // 3秒后自动取消
        mHandler.postDelayed(() -> mNotifMgr.cancel(ID_SYNC_STATUS), 3000);
    }

    /**
     * 显示同步失败
     */
    public void showTimeSyncFailed(String source) {
        String title = "同步失败";
        String text = "无法从" + getSourceName(source) + "获取时间";

        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();

        mNotifMgr.notify(ID_SYNC_STATUS, notification);

        // 5秒后自动取消
        mHandler.postDelayed(() -> mNotifMgr.cancel(ID_SYNC_STATUS), 5000);
    }

    /**
     * 清除所有通知
     */
    public void clearAll() {
        mNotifMgr.cancel(ID_SYNC_STATUS);
    }

    // ================================================================
    //  秒表功能 (预留)
    // ================================================================

    /**
     * 启动秒表
     */
    public void startStopwatch() {
        // 秒表功能预留，当前版本未实现
        Log.d(TAG, "startStopwatch: not implemented");
    }

    /**
     * 暂停秒表
     */
    public void pauseStopwatch() {
        Log.d(TAG, "pauseStopwatch: not implemented");
    }

    /**
     * 停止秒表
     */
    public void stopStopwatch() {
        Log.d(TAG, "stopStopwatch: not implemented");
    }

    // ================================================================
    //  私有方法
    // ================================================================

    private PendingIntent createMainPendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private String getSourceName(String source) {
        switch (source) {
            case "taobao": return "淘宝";
            case "meituan": return "美团";
            case "local": return "本地";
            default: return source;
        }
    }
}
