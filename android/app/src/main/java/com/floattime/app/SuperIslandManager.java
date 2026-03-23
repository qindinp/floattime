package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 超级岛管理器
 * 
 * 实现小米超级岛支持：
 * 1. 使用 Bubble API 显示超级岛
 * 2. 支持 MIUI HyperOS 3.0+
 * 3. 与 ShizukuManager 配合绕过白名单
 */
public class SuperIslandManager {

    private static final String TAG = "SuperIslandManager";
    
    // 通知渠道
    private static final String CHANNEL_ID = "float_time_super_island";
    private static final String CHANNEL_NAME = "悬浮时间超级岛";
    private static final int NOTIFICATION_ID = 20240322;

    private final Context mContext;
    private final NotificationManager mNotifMgr;
    private final ShizukuManager mShizukuManager;

    private boolean mIsEnabled = false;
    private String mCurrentTime = "--:--:--";
    private String mCurrentMillis = ".000";
    private String mCurrentSource = "悬浮时间";

    public SuperIslandManager(Context context) {
        mContext = context.getApplicationContext();
        mNotifMgr = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mShizukuManager = new ShizukuManager(context);
        createNotificationChannel();
        
        Log.d(TAG, "SuperIslandManager initialized | SDK: " + Build.VERSION.SDK_INT);
        Log.d(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        Log.d(TAG, "Shizuku status: " + mShizukuManager.getStatusDescription());
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
            channel.enableVibration(false);
            channel.setSound(null, null);
            
            // 启用气泡通知
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                channel.setAllowBubbles(true);
            }
            
            mNotifMgr.createNotificationChannel(channel);
        }
    }

    /**
     * 是否支持超级岛
     */
    public boolean isSupported() {
        // Android 12+ 支持 Bubble API
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        
        // 小米设备检查
        boolean isXiaomi = mShizukuManager.isXiaomiDevice();
        
        // 有 Shizuku 或 Root
        boolean hasPrivileges = mShizukuManager.hasShizuku() || mShizukuManager.hasRoot();
        
        Log.d(TAG, "isSupported: Xiaomi=" + isXiaomi + ", Privileges=" + hasPrivileges);
        
        // 如果是小米设备且有权限，支持超级岛
        // 否则回退到普通 Bubble 通知
        return true; // 始终返回 true，让通知显示
    }

    /**
     * 是否已启用
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * 获取 Shizuku 状态
     */
    public ShizukuManager getShizukuManager() {
        return mShizukuManager;
    }

    /**
     * 启用超级岛
     */
    public void enable(OnResultListener listener) {
        if (mShizukuManager.hasRoot() || mShizukuManager.hasShizuku()) {
            mShizukuManager.enableSuperIsland(listener);
        } else {
            if (listener != null) {
                listener.onFailure("需要 Root 或 Shizuku 才能启用超级岛");
            }
        }
        mIsEnabled = true;
    }

    /**
     * 显示超级岛通知
     */
    public void show(String time, String millis, String source) {
        mCurrentTime = time;
        mCurrentMillis = millis;
        mCurrentSource = source;
        
        try {
            Notification notification = buildSuperIslandNotification();
            if (notification != null) {
                mNotifMgr.notify(NOTIFICATION_ID, notification);
                Log.d(TAG, "Super Island shown: " + time + millis);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to show Super Island: " + e.getMessage());
            // 回退到普通通知
            showFallback(time, millis, source);
        }
    }

    /**
     * 更新超级岛
     */
    public void update(String time, String millis, String source) {
        show(time, millis, source);
    }

    /**
     * 隐藏超级岛
     */
    public void hide() {
        mNotifMgr.cancel(NOTIFICATION_ID);
        Log.d(TAG, "Super Island hidden");
    }

    /**
     * 构建超级岛通知
     */
    private Notification buildSuperIslandNotification() {
        // 创建指向 MainActivity 的 Intent
        Intent mainIntent = new Intent(mContext, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
            mContext, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 创建图标
        Icon icon = Icon.createWithResource(mContext, android.R.drawable.ic_lock_idle_alarm);

        // 创建 BubbleMetadata
        android.app.BubbleMetadata.Builder bubbleBuilder = new android.app.BubbleMetadata.Builder(
            mainPendingIntent,
            icon
        );
        
        // 设置 Bubble 属性
        bubbleBuilder.setDesiredHeight(400);
        bubbleBuilder.setAutoExpandBubble(false);
        bubbleBuilder.setSuppressNotification(false);
        
        android.app.BubbleMetadata bubbleMetadata = bubbleBuilder.build();

        // 构建通知
        Notification.Builder builder;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(mCurrentSource)
                .setContentText(mCurrentTime + mCurrentMillis)
                .setContentIntent(mainPendingIntent)
                .setBubbleMetadata(bubbleMetadata)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_HIGH);
        } else {
            // Android 12 回退
            builder = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(mCurrentTime + mCurrentMillis)
                .setContentText(mCurrentSource)
                .setContentIntent(mainPendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        return builder.build();
    }

    /**
     * 回退到普通通知
     */
    private void showFallback(String time, String millis, String source) {
        try {
            Intent mainIntent = new Intent(mContext, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                mContext, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(time + millis)
                .setContentText(source)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

            mNotifMgr.notify(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.e(TAG, "Fallback notification also failed: " + e.getMessage());
        }
    }

    /**
     * 销毁
     */
    public void destroy() {
        hide();
        if (mShizukuManager != null) {
            mShizukuManager.destroy();
        }
    }

    /**
     * 结果监听器
     */
    public interface OnResultListener {
        void onSuccess(String message);
        void onFailure(String error);
    }
}
