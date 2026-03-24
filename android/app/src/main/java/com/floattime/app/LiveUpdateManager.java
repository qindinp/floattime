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
 * LiveUpdateManager v2 - 实时更新通知管理器
 *
 * 支持:
 * - Android 16 (API 36) Notification.ProgressStyle Live Updates
 * - 锁屏实时进度条（秒数驱动的环形进度）
 * - Android 12+ 前台服务即时行为
 * - 兼容所有 Android 版本 (API 24+)
 * - 小米超级岛 (HyperOS Focus Notification)
 *
 * v2 改进：
 * - Live Update 状态文本更丰富（当前时间 + 来源）
 * - ProgressStyle 颜色方案改进
 * - 前台服务通知增加 Live Update 标识
 */
public class LiveUpdateManager {

    private static final String TAG = "LiveUpdateManager";

    public static final String CHANNEL_ID = "float_time_live_updates";
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final String CHANNEL_DESC = "实时显示校准时间 (Live Update)";

    public static final int ID_CLOCK = 20240320;
    public static final int ID_SYNC_STATUS = 20240321;

    private static final int PROGRESS_MAX = 60;

    private final Context mContext;
    private final NotificationManager mNotifMgr;
    private final Handler mHandler;
    private final boolean mSupportsProgressStyle;

    private SuperIslandManager mSuperIsland;

    // 反射结果缓存
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

        Log.d(TAG, "LiveUpdateManager v2 initialized | API=" + Build.VERSION.SDK_INT
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
    //  反射缓存
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

            sBuilderSetProgressStyle = Notification.Builder.class.getMethod(
                    "setProgressStyle", sProgressStyleClass);

            sReflectionCached = true;
            Log.d(TAG, "ProgressStyle reflection cached successfully");
        } catch (Exception e) {
            Log.w(TAG, "ProgressStyle reflection cache failed (expected on pre-36): " + e.getMessage());
            sReflectionCached = true;
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
     * 创建时钟通知 (用于 startForeground 首次调用，不带网络断开保护)
     */
    public Notification createClockNotification(Context ctx,
                                                String timeStr, String millisStr,
                                                String source) {
        return createClockNotification(ctx, timeStr, millisStr, source, false);
    }

    /**
     * 创建时钟通知 (带主题，仅构建不发送)
     *
     * 注意：此方法仅构建通知对象，不执行 notify()。
     * 适用于 startForeground() 等需要直接返回 Notification 的场景。
     */
    @SuppressWarnings("deprecation")
    public Notification createClockNotification(Context ctx,
                                                String timeStr, String millisStr,
                                                String source, boolean isNight) {
        PendingIntent pi = createMainPendingIntent(ctx);

        Notification notification;
        if (mSupportsProgressStyle) {
            Notification.Builder nativeBuilder = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(timeStr + millisStr)
                    .setContentText(source + " · Live Update")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setForegroundServiceBehavior(
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 1 : 0);

            try {
                Object progressStyle = buildProgressStyle(timeStr, isNight);
                if (progressStyle != null && sBuilderSetProgressStyle != null) {
                    sBuilderSetProgressStyle.invoke(nativeBuilder, progressStyle);
                }
            } catch (Exception e) {
                Log.e(TAG, "ProgressStyle apply failed: " + e.getMessage());
            }

            notification = nativeBuilder.build();
        } else {
            notification = new NotificationCompat.Builder(ctx, CHANNEL_ID)
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
        }

        // 注入焦点通知 JSON（不发送独立通知，不执行网络断开）
        try {
            String focusJson = mSuperIsland.buildFocusParamJson(timeStr, millisStr, source);
            if (focusJson != null) {
                notification.extras.putString(FocusParamBuilder.KEY_FOCUS_PARAM, focusJson);
            }
        } catch (Exception e) {
            Log.e(TAG, "SuperIsland focus param injection failed: " + e.getMessage());
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
     *
     * Android 16+: 使用原生 ProgressStyle + 注入焦点通知 JSON
     * 小米 HyperOS: 发送通知时带 xmsf 网络断开保护（保证超级岛白名单绕过）
     */
    @SuppressWarnings("deprecation")
    public void updateClock(Context ctx, String timeStr, String millisStr,
                            String source, boolean isNight) {
        PendingIntent pi = createMainPendingIntent(ctx);

        // 构建通知
        Notification notification;
        if (mSupportsProgressStyle) {
            Notification.Builder nativeBuilder = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(timeStr + millisStr)
                    .setContentText(source + " · Live Update")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setForegroundServiceBehavior(
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 1 : 0);

            // Android 16 ProgressStyle
            try {
                Object progressStyle = buildProgressStyle(timeStr, isNight);
                if (progressStyle != null && sBuilderSetProgressStyle != null) {
                    sBuilderSetProgressStyle.invoke(nativeBuilder, progressStyle);
                }
            } catch (Exception e) {
                Log.e(TAG, "ProgressStyle apply failed: " + e.getMessage());
            }

            notification = nativeBuilder.build();
        } else {
            notification = new NotificationCompat.Builder(ctx, CHANNEL_ID)
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
        }

        // 注入小米超级岛 V3 焦点通知 JSON
        try {
            String focusJson = mSuperIsland.buildFocusParamJson(timeStr, millisStr, source);
            if (focusJson != null) {
                notification.extras.putString(FocusParamBuilder.KEY_FOCUS_PARAM, focusJson);
            }
        } catch (Exception e) {
            Log.e(TAG, "SuperIsland focus param injection failed: " + e.getMessage());
        }

        // 发送通知 — HyperOS 下带 xmsf 网络断开保护
        notifyWithNetworkCut(notification);
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
    //  串行化通知发送（xmsf 网络断开保护）
    // ================================================================

    private static final long NETWORK_CUT_DURATION_MS = 50L;

    /**
     * 发送通知，HyperOS 下带 xmsf 网络断开保护
     *
     * 参考 Capsulyric notifyWithNetworkCut:
     *   ① 断网 → ② 发通知 → ③ 等50ms → ④ 恢复网络
     */
    private void notifyWithNetworkCut(Notification notification) {
        ShizukuIslandHelper shizuku = mSuperIsland.getShizukuHelper();
        if (shizuku != null && shizuku.isReady()) {
            try {
                // ① 同步断开 xmsf 网络
                boolean disabled = shizuku.setXmsfNetworkingSync(false);
                Log.d(TAG, "notifyWithNetworkCut: xmsf disabled=" + disabled);

                // ② 发送通知
                mNotifMgr.notify(ID_CLOCK, notification);

                // ③ 等待 50ms
                Thread.sleep(NETWORK_CUT_DURATION_MS);

                // ④ 恢复网络
                shizuku.setXmsfNetworkingSync(true);
                Log.d(TAG, "notifyWithNetworkCut: xmsf restored");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                shizuku.setXmsfNetworkingSync(true);
            } catch (Exception e) {
                Log.e(TAG, "notifyWithNetworkCut failed: " + e.getMessage());
                shizuku.setXmsfNetworkingSync(true);
            }
        } else {
            // 非 HyperOS 或 Shizuku 未就绪，直接发送
            mNotifMgr.notify(ID_CLOCK, notification);
        }
    }

    // ================================================================
    //  Android 16 ProgressStyle (Live Update)
    // ================================================================

    /**
     * 构建 ProgressStyle 对象
     * 60 个 segment 代表秒数，交替颜色模拟时钟刻度
     * 3 个关键时间点标记：0秒(绿)、30秒(红)、当前秒(蓝)
     */
    private Object buildProgressStyle(String timeStr, boolean isNight) {
        if (Build.VERSION.SDK_INT < 36 || sProgressStyleClass == null) return null;

        int currentSecond = parseSecond(timeStr);

        // 构建 60 个 segment
        List<Object> segments = new ArrayList<>(PROGRESS_MAX);
        for (int i = 0; i < PROGRESS_MAX; i++) {
            int color;
            if (isNight) {
                color = i % 2 == 0 ? 0xFF555555 : 0xFF444444;
            } else {
                color = i % 2 == 0 ? 0xFFCCCCCC : 0xFFE8E8E8;
            }
            segments.add(createSegment(1, color));
        }

        // 关键时间点标记
        List<Object> points = new ArrayList<>(3);
        points.add(createPoint(0, 0xFF4CAF50));        // 0 秒 = 绿色起点
        points.add(createPoint(30, 0xFFFF5722));        // 30 秒 = 红色中点
        points.add(createPoint(currentSecond, 0xFF2196F3)); // 当前秒 = 蓝色

        try {
            Object style = sProgressStyleClass.getDeclaredConstructor().newInstance();
            sSetStyledByProgress.invoke(style, false);
            sSetProgress.invoke(style, currentSecond);
            sSetProgressSegments.invoke(style, segments);
            sSetProgressPoints.invoke(style, points);
            return style;
        } catch (Exception e) {
            Log.e(TAG, "buildProgressStyle failed: " + e.getMessage());
            return null;
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
     * 创建 ProgressStyle.Segment
     */
    private Object createSegment(int length, int color) {
        if (sSegmentClass == null) return fallbackBundle(length, color);
        try {
            Object segment = sSegmentClass.getConstructor(int.class).newInstance(length);
            Method setColor = sSegmentClass.getMethod("setColor", int.class);
            setColor.invoke(segment, color);
            return segment;
        } catch (Exception e) {
            return fallbackBundle(length, color);
        }
    }

    /**
     * 创建 ProgressStyle.Point
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
