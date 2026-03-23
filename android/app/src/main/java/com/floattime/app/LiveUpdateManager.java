package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * LiveUpdateManager - 实时更新通知管理器
 *
 * 支持:
 * - Android 16 (API 36) Notification.ProgressStyle Live Updates
 * - Android 12+ 前台服务即时行为
 * - 兼容所有 Android 版本 (API 24+)
 * - 小米超级岛 (HyperOS Focus Notification)
 */
public class LiveUpdateManager {

    private static final String TAG = "LiveUpdateManager";

    private static final String CHANNEL_ID   = "float_time_live_updates";
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final String CHANNEL_DESC = "实时显示校准时间";

    public static final int ID_CLOCK = 20240320;        // 时钟通知 (与前台服务共用)
    public static final int ID_SYNC_STATUS = 20240321;  // 同步状态

    /** ProgressStyle 总进度范围 (秒表 60 秒) */
    private static final int PROGRESS_MAX = 60;

    private final Context mContext;
    private final NotificationManager mNotifMgr;
    private final Handler mHandler;

    /** 是否支持 Android 16 ProgressStyle */
    private final boolean mSupportsProgressStyle;

    /** 小米超级岛管理器 */
    private SuperIslandManager mSuperIsland;

    public LiveUpdateManager(Context context) {
        mContext = context.getApplicationContext();
        mNotifMgr = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
        createChannel();

        mSupportsProgressStyle = Build.VERSION.SDK_INT >= 36;
        mSuperIsland = new SuperIslandManager(mContext);

        Log.d(TAG, "LiveUpdateManager initialized | API=" + Build.VERSION.SDK_INT
                + " | ProgressStyle=" + mSupportsProgressStyle
                + " | HyperOS=" + mSuperIsland.isHyperOS());
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
     * 检查是否支持 Live Updates (所有设备都支持基础通知)
     */
    public boolean isSupported() {
        return true;
    }

    /**
     * 是否支持 Android 16 ProgressStyle
     */
    public boolean supportsProgressStyle() {
        return mSupportsProgressStyle;
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
    @SuppressWarnings("deprecation")
    public Notification createClockNotification(Context ctx,
                                               String timeStr, String millisStr,
                                               String source, boolean isNight) {
        PendingIntent pi = createMainPendingIntent(ctx);

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

        // Android 12+ 前台服务即时行为
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        Notification notification = builder.build();

        // =============================================
        // Android 16 (API 36) ProgressStyle Live Updates
        // =============================================
        if (mSupportsProgressStyle) {
            try {
                applyProgressStyle(notification, timeStr, millisStr, isNight);
            } catch (Exception e) {
                Log.e(TAG, "ProgressStyle apply failed: " + e.getMessage());
            }
        }

        // =============================================
        // 小米超级岛 (HyperOS Focus Notification)
        // =============================================
        try {
            mSuperIsland.applyFocusExtras(notification, timeStr, millisStr, source);
        } catch (Exception e) {
            Log.e(TAG, "SuperIsland extras failed: " + e.getMessage());
        }

        return notification;
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
        mSuperIsland.hide();
    }

    // ================================================================
    //  Android 16 ProgressStyle (API 36)
    // ================================================================

    /**
     * 将 Notification.ProgressStyle 应用到通知上
     *
     * 设计思路:
     * - 60 个 segment 代表 60 秒，交替着色形成时钟刻度感
     * - progress 设为当前秒数，让 tracker icon 位于当前位置
     * - 在 0s 和 30s 处设置 point 标记 (像时钟的 12 和 6)
     */
    private void applyProgressStyle(Notification notification,
                                    String timeStr, String millisStr,
                                    boolean isNight) {
        if (Build.VERSION.SDK_INT < 36) return;

        // 解析当前秒数
        int currentSecond = 0;
        try {
            String[] parts = timeStr.split(":");
            if (parts.length >= 3) {
                currentSecond = Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) {}

        // 构建 60 个 segment，交替颜色模拟时钟刻度
        List<Object> segments = new ArrayList<>();
        for (int i = 0; i < PROGRESS_MAX; i++) {
            segments.add(createSegment(1, i % 2 == 0
                    ? (isNight ? 0xFF555555 : 0xFFCCCCCC)
                    : (isNight ? 0xFF444444 : 0xFFE8E8E8)));
        }

        // 关键时间点标记
        List<Object> points = new ArrayList<>();
        points.add(createPoint(0, 0xFF4CAF50));              // 12点位置 (绿)
        points.add(createPoint(30, 0xFFFF5722));             // 6点位置 (红)
        points.add(createPoint(currentSecond, 0xFF2196F3));  // 当前秒 (蓝)

        // 构建 ProgressStyle 并设置到通知上
        Object progressStyle = createProgressStyle(
                currentSecond,
                segments,
                points,
                isNight
        );

        if (progressStyle != null) {
            // 反射调用: notification.Builder.setProgressStyle(style)
            // 然后用 setBuilder 重建通知
            applyStyleToNotification(notification, progressStyle);
        }
    }

    /**
     * 通过反射创建 Notification.ProgressStyle.Segment
     */
    private Object createSegment(int length, int color) {
        try {
            Class<?> segmentClass = Class.forName("android.app.Notification$ProgressStyle$Segment");
            return segmentClass
                    .getConstructor(int.class)
                    .newInstance(length);
            // setColor 需要另外调用
        } catch (Exception e) {
            // Fallback: 用 Bundle 表示
            Bundle b = new Bundle();
            b.putInt("length", length);
            b.putInt("color", color);
            return b;
        }
    }

    /**
     * 通过反射创建 Notification.ProgressStyle.Point
     */
    private Object createPoint(int position, int color) {
        try {
            Class<?> pointClass = Class.forName("android.app.Notification$ProgressStyle$Point");
            return pointClass
                    .getConstructor(int.class)
                    .newInstance(position);
        } catch (Exception e) {
            Bundle b = new Bundle();
            b.putInt("position", position);
            b.putInt("color", color);
            return b;
        }
    }

    /**
     * 通过反射创建 Notification.ProgressStyle
     */
    private Object createProgressStyle(int progress,
                                        List<Object> segments,
                                        List<Object> points,
                                        boolean isNight) {
        if (Build.VERSION.SDK_INT < 36) return null;

        try {
            Class<?> styleClass = Class.forName("android.app.Notification$ProgressStyle");
            Object style = styleClass.getDeclaredConstructor().newInstance();

            // setStyledByProgress(false) — 不自动着色
            styleClass.getMethod("setStyledByProgress", boolean.class)
                    .invoke(style, false);

            // setProgress(currentSecond)
            styleClass.getMethod("setProgress", int.class)
                    .invoke(style, progress);

            // setProgressSegments(List)
            styleClass.getMethod("setProgressSegments", java.util.List.class)
                    .invoke(style, segments);

            // setProgressPoints(List)
            styleClass.getMethod("setProgressPoints", java.util.List.class)
                    .invoke(style, points);

            return style;
        } catch (Exception e) {
            Log.e(TAG, "createProgressStyle reflection failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 将 ProgressStyle 应用到通知
     * 通过 Notification.Builder.setProgressStyle(style).build() 合并 extras
     */
    private void applyStyleToNotification(Notification notification, Object progressStyle) {
        if (Build.VERSION.SDK_INT < 36) return;

        try {
            // 获取 Notification 的 extras
            Bundle extras = notification.extras;

            // 通过 Builder 重建通知
            // 这里直接操作 extras 来注入 ProgressStyle
            // ProgressStyle.build() 会把 style 写入 extras
            Class<?> styleClass = progressStyle.getClass();

            // 调用 ProgressStyle.setBuilder(builder).build()
            // 我们需要找到原始 builder 并重新构建
            Notification.Builder nativeBuilder = new Notification.Builder(mContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(extras.getString(Notification.EXTRA_TITLE, ""))
                    .setContentText(extras.getString(Notification.EXTRA_TEXT, ""))
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // FOREGROUND_SERVICE_IMMEDIATE = 1
                nativeBuilder.setForegroundBehavior(1);
            }

            // 调用 setProgressStyle
            nativeBuilder.getClass()
                    .getMethod("setProgressStyle", styleClass)
                    .invoke(nativeBuilder, progressStyle);

            // 将 built notification 的 extras 合并回原通知
            Notification built = nativeBuilder.build();
            extras.putAll(built.extras);

        } catch (Exception e) {
            Log.e(TAG, "applyStyleToNotification failed: " + e.getMessage());
        }
    }

    // ================================================================
    //  秒表功能 (预留)
    // ================================================================

    /**
     * 启动秒表
     */
    public void startStopwatch() {
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
