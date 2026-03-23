package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.xzakota.hyper.notification.focus.FocusNotification;
import com.xzakota.hyper.notification.focus.FocusTemplate;

/**
 * 超级岛管理器
 * 
 * 使用 HyperNotification API 实现小米超级岛支持
 * 
 * 参考：https://github.com/xzakota/HyperNotification
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
    private String mCurrentTime = "--:--:--";
    private String mCurrentMillis = ".000";
    private String mCurrentSource = "悬浮时间";

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
            channel.enableVibration(false);
            channel.setSound(null, null);
            
            mNotifMgr.createNotificationChannel(channel);
        }
    }

    /**
     * 是否支持超级岛
     */
    public boolean isSupported() {
        // 需要 Android 12+ (S = 31)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        
        // 小米/红米/POCO 设备
        boolean isXiaomi = Build.MANUFACTURER.toLowerCase().contains("xiaomi") ||
               Build.BRAND.toLowerCase().contains("xiaomi") ||
               Build.MANUFACTURER.toLowerCase().contains("redmi") ||
               Build.BRAND.toLowerCase().contains("redmi") ||
               Build.MANUFACTURER.toLowerCase().contains("poco") ||
               Build.BRAND.toLowerCase().contains("poco");
        
        Log.d(TAG, "isSupported: Xiaomi=" + isXiaomi + ", SDK=" + Build.VERSION.SDK_INT);
        
        return true; // 让系统决定是否显示
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
        try {
            // 创建指向 MainActivity 的 Intent
            Intent mainIntent = new Intent(mContext, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent mainPendingIntent = PendingIntent.getActivity(
                mContext, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // ✅ 使用 HyperNotification API 构建超级岛数据
            Bundle extras = FocusNotification.buildV2(template -> {
                // 启用浮动显示（超级岛）
                template.setEnableFloat(true);
                template.setTicker(mCurrentTime + mCurrentMillis);
                
                // 基础信息（展开前显示）
                template.baseInfo(info -> {
                    info.setType(1);
                    info.setTitle(mCurrentTime + mCurrentMillis);
                    info.setContent(mCurrentSource);
                });
                
                // 提示信息（展开后显示）
                template.hintInfo(info -> {
                    info.setType(1);
                    info.setTitle(mCurrentSource);
                    info.setContent("实时校准时间");
                });
            });

            // ✅ 构建通知，使用 HyperNotification 的 extras
            Notification.Builder builder;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder = new Notification.Builder(mContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(mCurrentTime + mCurrentMillis)
                    .setContentText(mCurrentSource)
                    .setContentIntent(mainPendingIntent)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setTicker(mCurrentTime + mCurrentMillis);
                
                // 添加超级岛数据
                if (extras != null) {
                    builder.addExtras(extras);
                }
            } else {
                builder = new Notification.Builder(mContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(mCurrentTime + mCurrentMillis)
                    .setContentText(mCurrentSource)
                    .setContentIntent(mainPendingIntent)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC);
                
                if (extras != null) {
                    builder.addExtras(extras);
                }
            }

            return builder.build();
            
        } catch (Exception e) {
            Log.e(TAG, "buildSuperIslandNotification error: " + e.getMessage());
            return null;
        }
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
    }

    /**
     * 结果监听器
     */
    public interface OnResultListener {
        void onSuccess(String message);
        void onFailure(String error);
    }
}
