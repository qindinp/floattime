package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.lang.reflect.Method;
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
 *
 * 优化:
 * - 反射结果缓存: Class/Method 只查找一次
 * - CHANNEL_ID 改为 public 常量，与其他类统一
 * - Segment 颜色正确应用 (修复原 bug)
 */
public class LiveUpdateManager {

    private static final String TAG = "LiveUpdateManager";

    // 修复: 改为 public，FloatTimeService 和 SuperIslandManager 统一引用
    public static final String CHANNEL_ID = "float_time_live_updates";
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final String CHANNEL_DESC = "实时显示校准时间";

    public static final int ID_CLOCK = 20240320;
    public static final int ID_SYNC_STATUS = 20240321;

    private static final int PROGRESS_MAX = 60;

    private final Context mContext;
    private final NotificationManager mNotifMgr;
    private final Handler mHandler;
    private final boolean mSupportsProgressStyle;

    private SuperIslandManager mSuperIsland;

    // 优化: 反射结果缓存
    private static Class<?> sProgressStyleClass;
    private static Method sSetStyledByProgress;
    private static Method sSetProgress;
    private static Method sSetProgressSegments;
    private static Method sSetProgressPoints;
    private static Method sBuilderSetProgressStyle;
    private static Class<?> sSegmentClass;
    private static Class<?> sPointClass;
    private static boolean sReflectionCached = false;

    public LiveUpdateManager(Context context) {
        mContext = context.getApplicationContext();
        mNotifMgr = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
        createChannel();

        mSupportsProgressStyle = Build.VERSION.SDK_INT >= 36;
        mSuperIsland = new SuperIslandManager(mContext);

        // 初始化 Shizuku 白名单绕过
        mSuperIsland.init();

        if (mSupportsProgressStyle && !sReflectionCached) {
            cacheReflection();
        }

        Log.d(TAG, "LiveUpdateManager initialized | API=" + Build.VERSION.SDK_INT
                + " | ProgressStyle=" + mSupportsProgressStyle
                + " | HyperOS=" + mSuperIsland.isHyperOS()
                + " | Shizuku=" + mSuperIsland.isShizukuReady()
                + " | WhitelistBypass=" + mSuperIsland.isWhitelistBypassed());
    }

    /**
     * 获取超级岛管理器引用（供外部查询 Shizuku 状态等）
     */
    public SuperIslandManager getSuperIslandManager() {
        return mSuperIsland;
    }

    // ================================================================
    //  反射缓存 (优化: 只执行一次)
    // ================================================================

    private static synchronized void cacheReflection() {
        if (sReflectionCached) return;
        try {
            sProgressStyleClass = Class.forName("android.app.Notification$ProgressStyle");
            sSetStyledByProgress = sProgressStyleClass.getMethod("setStyledByProgress", boolean.class);
            sSetProgress = sProgressStyleClass.getMethod("setProgress", int.class);
            sSetProgressSegments = sProgressStyleClass.getMethod("setProgressSegments", List.class);
            sSetProgressPoints = sProgressStyleClass.getMethod("setProgressPoints", List.class);

            sSegmentClass = Class.forName("android.app.Notification$ProgressStyle$Segment");
            sPointClass = Class.forName("android.app.Notification$ProgressStyle$Point");

            // 缓存 Builder.setProgressStyle
            sBuilderSetProgressStyle = Notification.Builder.class.getMethod(
                    "setProgressStyle", sProgressStyleClass);

            sReflectionCached = true;
            Log.d(TAG, "ProgressStyle reflection cached successfully");
        } catch (Exception e) {
            Log.w(TAG, "ProgressStyle reflection cache failed (expected on pre-36): " + e.getMessage());
            sReflectionCached = true; // 标记已尝试，不再重复
        }
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

    public boolean isSupported() {
        return true;
    }

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

        Notification notification = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(timeStr + millisStr)
                .setContentText(source)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                ? NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                                : NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
                .build();

        // Android 16 ProgressStyle
        if (mSupportsProgressStyle) {
            try {
                applyProgressStyle(notification, timeStr, millisStr, isNight);
            } catch (Exception e) {
                Log.e(TAG, "ProgressStyle apply failed: " + e.getMessage());
            }
        }

        // 小米超级岛
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
    public void updateClock(Context ctx, String timeStr, String millisStr,
                            String source, boolean isNight) {
        Notification notification = createClockNotification(ctx, timeStr, millisStr, source, isNight);
        mNotifMgr.notify(ID_CLOCK, notification);
    }

    // ================================================================
    //  同步状态通知
    // ================================================================

    public void showTimeSyncing(String source) {
        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("正在同步...")
                .setContentText("从" + getSourceName(source) + "获取时间")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        mNotifMgr.notify(ID_SYNC_STATUS, notification);
    }

    public void showTimeSyncSuccess(String source, long offsetMs) {
        String text = String.format("%s | 偏移: %+.0fms",
                getSourceName(source), (double) offsetMs);

        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("时间已同步")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        mNotifMgr.notify(ID_SYNC_STATUS, notification);
        mHandler.postDelayed(() -> mNotifMgr.cancel(ID_SYNC_STATUS), 3000);
    }

    public void showTimeSyncFailed(String source) {
        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("同步失败")
                .setContentText("无法从" + getSourceName(source) + "获取时间")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        mNotifMgr.notify(ID_SYNC_STATUS, notification);
        mHandler.postDelayed(() -> mNotifMgr.cancel(ID_SYNC_STATUS), 5000);
    }

    public void clearAll() {
        mNotifMgr.cancel(ID_SYNC_STATUS);
        mSuperIsland.hide();
    }

    // ================================================================
    //  Android 16 ProgressStyle (优化: 反射缓存 + 颜色修复)
    // ================================================================

    private void applyProgressStyle(Notification notification,
                                    String timeStr, String millisStr,
                                    boolean isNight) {
        if (Build.VERSION.SDK_INT < 36 || sProgressStyleClass == null) return;

        int currentSecond = parseSecond(timeStr);

        // 构建 60 个 segment，交替颜色模拟时钟刻度
        List<Object> segments = new ArrayList<>(PROGRESS_MAX);
        for (int i = 0; i < PROGRESS_MAX; i++) {
            segments.add(createSegment(1, i % 2 == 0
                    ? (isNight ? 0xFF555555 : 0xFFCCCCCC)
                    : (isNight ? 0xFF444444 : 0xFFE8E8E8)));
        }

        // 关键时间点标记
        List<Object> points = new ArrayList<>(3);
        points.add(createPoint(0, 0xFF4CAF50));
        points.add(createPoint(30, 0xFFFF5722));
        points.add(createPoint(currentSecond, 0xFF2196F3));

        Object progressStyle = createProgressStyle(currentSecond, segments, points);
        if (progressStyle != null) {
            applyStyleToNotification(notification, progressStyle);
        }
    }

    private int parseSecond(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            if (parts.length >= 3) {
                return Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    /**
     * 创建 ProgressStyle.Segment (优化: 缓存反射, 正确应用颜色)
     */
    private Object createSegment(int length, int color) {
        if (sSegmentClass == null) return fallbackBundle(length, color);
        try {
            Object segment = sSegmentClass.getConstructor(int.class).newInstance(length);
            // 修复: 原代码创建了 Segment 但没有设置颜色
            Method setColor = sSegmentClass.getMethod("setColor", int.class);
            setColor.invoke(segment, color);
            return segment;
        } catch (Exception e) {
            return fallbackBundle(length, color);
        }
    }

    /**
     * 创建 ProgressStyle.Point (优化: 缓存反射, 正确应用颜色)
     */
    private Object createPoint(int position, int color) {
        if (sPointClass == null) {
            Bundle b = new Bundle();
            b.putInt("position", position);
            b.putInt("color", color);
            return b;
        }
        try {
            Object point = sPointClass.getConstructor(int.class).newInstance(position);
            // 修复: 设置颜色
            Method setColor = sPointClass.getMethod("setColor", int.class);
            setColor.invoke(point, color);
            return point;
        } catch (Exception e) {
            Bundle b = new Bundle();
            b.putInt("position", position);
            b.putInt("color", color);
            return b;
        }
    }

    private Bundle fallbackBundle(int length, int color) {
        Bundle b = new Bundle();
        b.putInt("length", length);
        b.putInt("color", color);
        return b;
    }

    /**
     * 创建 ProgressStyle (优化: 缓存反射)
     */
    private Object createProgressStyle(int progress, List<Object> segments, List<Object> points) {
        if (Build.VERSION.SDK_INT < 36 || sProgressStyleClass == null) return null;

        try {
            Object style = sProgressStyleClass.getDeclaredConstructor().newInstance();
            sSetStyledByProgress.invoke(style, false);
            sSetProgress.invoke(style, progress);
            sSetProgressSegments.invoke(style, segments);
            sSetProgressPoints.invoke(style, points);
            return style;
        } catch (Exception e) {
            Log.e(TAG, "createProgressStyle failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 将 ProgressStyle 应用到通知 (优化: 缓存反射)
     */
    private void applyStyleToNotification(Notification notification, Object progressStyle) {
        if (Build.VERSION.SDK_INT < 36) return;

        try {
            Bundle extras = notification.extras;

            Notification.Builder nativeBuilder = new Notification.Builder(mContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(extras.getString(Notification.EXTRA_TITLE, ""))
                    .setContentText(extras.getString(Notification.EXTRA_TEXT, ""))
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setForegroundServiceBehavior(
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 1 : 0);

            if (sBuilderSetProgressStyle != null) {
                sBuilderSetProgressStyle.invoke(nativeBuilder, progressStyle);
            }

            Notification built = nativeBuilder.build();
            extras.putAll(built.extras);

        } catch (Exception e) {
            Log.e(TAG, "applyStyleToNotification failed: " + e.getMessage());
        }
    }

    // ================================================================
    //  秒表功能 (预留)
    // ================================================================

    public void startStopwatch() { Log.d(TAG, "startStopwatch: not implemented"); }
    public void pauseStopwatch() { Log.d(TAG, "pauseStopwatch: not implemented"); }
    public void stopStopwatch() { Log.d(TAG, "stopStopwatch: not implemented"); }

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
