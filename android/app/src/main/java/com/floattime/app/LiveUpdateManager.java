package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Android 16 Live Updates 管理器
 *
 * 使用 Notification.ProgressStyle (API 36+) 实现:
 * - 时间显示进度条 (从午夜到当前时间)
 * - 时间同步进度条
 * - 秒表计时进度条
 *
 * Live Updates 条件:
 * 1. 使用 Notification.ProgressStyle 模板
 * 2. setOngoing(true) 标记为进行中
 * 3. 状态栏简短摘要
 * 4. POST_PROMOTED_NOTIFICATIONS 权限 (已声明)
 * 5. requestPromotedOngoing API 调用
 *
 * 兼容 API < 36: 回退到普通 Notification
 */
public class LiveUpdateManager {

    private static final String TAG = "LiveUpdateManager";

    private static final String CHANNEL_ID   = "float_time_live_updates";
    private static final String CHANNEL_NAME = "实时活动";
    private static final String CHANNEL_DESC = "显示时间同步、秒表等实时状态";

    public static final int ID_TIME_SYNC  = 3001;
    public static final int ID_STOPWATCH  = 3002;
    public static final int ID_COUNTDOWN  = 3003;

    // 24 小时 = 86400 秒，进度条总量
    private static final int DAY_PROGRESS_MAX = 1000;

    private final Context mContext;
    private final NotificationManager mNotifMgr;
    private final Handler mHandler;

    // 秒表
    private long     mStopwatchBase = 0;
    private boolean  mStopwatchOn   = false;
    private Runnable mStopwatchTick;
    private Object   mStopwatchTracker;  // ProgressStyle tracker

    public LiveUpdateManager(Context context) {
        mContext  = context.getApplicationContext();
        mNotifMgr = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mHandler  = new Handler(Looper.getMainLooper());
        createChannel();
        Log.d(TAG, "init | API=" + Build.VERSION.SDK_INT);
    }

    // ================================================================
    //  渠道
    // ================================================================

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(CHANNEL_DESC);
        ch.setShowBadge(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 100});
        ch.enableLights(true);
        ch.setLightColor(0xFF4A90E2);
        ch.setBypassDnd(true);
        ch.setSound(null, null);
        mNotifMgr.createNotificationChannel(ch);
    }

    // ================================================================
    //  公共
    // ================================================================

    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= 36;
    }

    public void clearAll() {
        stopStopwatch();
        mNotifMgr.cancel(ID_TIME_SYNC);
        mNotifMgr.cancel(ID_COUNTDOWN);
    }

    // ================================================================
    //  🕐 时钟通知 (前台服务主通知)
    // ================================================================

    /**
     * 创建时钟通知。
     * API 36+: Notification.ProgressStyle
     * API < 36: 普通 Notification
     */
    public Notification createClockNotification(Context ctx,
                                                 String timeStr, String millisStr,
                                                 String source, boolean isNight) {
        if (isSupported()) return createClockLive(ctx, timeStr, millisStr, source);
        return createClockLegacy(ctx, timeStr, millisStr, source);
    }

    /** API 36+: ProgressStyle */
    private Notification createClockLive(Context ctx, String timeStr,
                                          String millisStr, String source) {
        try {
            int progress = getDayProgress();

            Notification.ProgressStyle ps = new Notification.ProgressStyle()
                    .setStyledByProgress(false)
                    .setProgress(progress);

            PendingIntent pi = mainPI(ctx, 99);

            Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("🕐 " + timeStr + millisStr)
                    .setContentText(source)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setStyle(ps);

            Notification n = b.build();
            tryPromote(ctx, n);
            return n;
        } catch (Exception e) {
            Log.e(TAG, "createClockLive: " + e.getMessage());
            return createClockLegacy(ctx, timeStr, millisStr, source);
        }
    }

    /** 更新时钟 (每秒调用) */
    public void updateClock(Context ctx, String timeStr, String millisStr, String source) {
        if (!isSupported()) return;
        try {
            int progress = getDayProgress();
            Notification.ProgressStyle ps = new Notification.ProgressStyle()
                    .setStyledByProgress(false)
                    .setProgress(progress);

            PendingIntent pi = mainPI(ctx, 99);

            Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("🕐 " + timeStr + millisStr)
                    .setContentText(source)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setStyle(ps)
                    .build();

            mNotifMgr.notify(99, n);
        } catch (Exception e) {
            Log.w(TAG, "updateClock: " + e.getMessage());
        }
    }

    /** API < 36 回退 */
    private Notification createClockLegacy(Context ctx, String timeStr,
                                            String millisStr, String source) {
        PendingIntent pi = mainPI(ctx, 99);
        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("🕐 " + timeStr + millisStr)
                .setContentText(source + " · 点击打开")
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    // ================================================================
    //  🔄 时间同步 Live Updates
    // ================================================================

    public void showTimeSyncing(String source) {
        String name = srcName(source);

        if (isSupported()) {
            try {
                Notification.ProgressStyle ps = new Notification.ProgressStyle()
                        .setStyledByProgress(false)
                        .setProgress(0);

                Notification n = new Notification.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_popup_sync)
                        .setContentTitle("同步中")
                        .setContentText("从 " + name + " 获取…")
                        .setContentIntent(mainPI(mContext, ID_TIME_SYNC))
                        .setOngoing(true)
                        .setProgress(0, 0, true)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setStyle(ps)
                        .build();

                mNotifMgr.notify(ID_TIME_SYNC, n);
                tryPromote(mContext, n);
                return;
            } catch (Exception e) {
                Log.w(TAG, "showTimeSyncing: " + e.getMessage());
            }
        }

        mNotifMgr.notify(ID_TIME_SYNC,
                new NotificationCompat.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_popup_sync)
                        .setContentTitle("同步中")
                        .setContentText("从 " + name + " 获取…")
                        .setOngoing(true).setProgress(0, 0, true).build());
    }

    public void showTimeSyncSuccess(String source, long offsetMs) {
        String name = srcName(source);
        String sign = offsetMs >= 0 ? "+" : "";
        String text = name + " 偏差 " + sign + offsetMs + "ms";

        if (isSupported()) {
            try {
                Notification.ProgressStyle ps = new Notification.ProgressStyle()
                        .setStyledByProgress(false)
                        .setProgress(100);

                Notification n = new Notification.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_input_get)
                        .setContentTitle("✅ 已校准")
                        .setContentText(text)
                        .setAutoCancel(true)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setStyle(ps)
                        .build();

                mNotifMgr.notify(ID_TIME_SYNC, n);
            } catch (Exception e) {
                Log.w(TAG, "showTimeSyncSuccess: " + e.getMessage());
            }
        } else {
            mNotifMgr.notify(ID_TIME_SYNC,
                    new NotificationCompat.Builder(mContext, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_input_get)
                            .setContentTitle("✅ 已校准")
                            .setContentText(text)
                            .setAutoCancel(true).build());
        }

        mHandler.postDelayed(() -> mNotifMgr.cancel(ID_TIME_SYNC), 3000);
    }

    public void showTimeSyncFailed(String source) {
        String name = srcName(source);

        if (isSupported()) {
            try {
                Notification.ProgressStyle ps = new Notification.ProgressStyle()
                        .setStyledByProgress(false)
                        .setProgress(0);

                Notification n = new Notification.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle("❌ 同步失败")
                        .setContentText("无法连接 " + name)
                        .setAutoCancel(true)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setStyle(ps)
                        .build();

                mNotifMgr.notify(ID_TIME_SYNC, n);
            } catch (Exception e) {
                Log.w(TAG, "showTimeSyncFailed: " + e.getMessage());
            }
        } else {
            mNotifMgr.notify(ID_TIME_SYNC,
                    new NotificationCompat.Builder(mContext, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_dialog_alert)
                            .setContentTitle("❌ 同步失败")
                            .setContentText("无法连接 " + name)
                            .setAutoCancel(true).build());
        }

        mHandler.postDelayed(() -> mNotifMgr.cancel(ID_TIME_SYNC), 5000);
    }

    // ================================================================
    //  ⏱ 秒表 Live Updates
    // ================================================================

    public void startStopwatch() {
        if (mStopwatchOn) return;
        mStopwatchOn   = true;
        mStopwatchBase = SystemClock.elapsedRealtime();

        PendingIntent pausePI = svcPI(mContext, "STOPWATCH_PAUSE", 10);
        PendingIntent stopPI  = svcPI(mContext, "STOPWATCH_STOP",  11);

        mStopwatchTick = new Runnable() {
            @Override public void run() {
                if (!mStopwatchOn) return;
                long el = SystemClock.elapsedRealtime() - mStopwatchBase;
                pushStopwatch(el, pausePI, stopPI);
                mHandler.postDelayed(this, 100);
            }
        };
        mHandler.post(mStopwatchTick);
    }

    private void pushStopwatch(long elapsedMs, PendingIntent pausePI, PendingIntent stopPI) {
        String text = fmtSW(elapsedMs);
        long sec = elapsedMs / 1000;

        if (isSupported()) {
            try {
                Notification.ProgressStyle ps = new Notification.ProgressStyle()
                        .setStyledByProgress(false)
                        .setProgress((int)(sec % 60) * 1000 / 60);

                Notification n = new Notification.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle("⏱ 秒表")
                        .setContentText(text)
                        .setOngoing(true)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .addAction(new Notification.Action.Builder(
                                android.R.drawable.ic_media_pause, "暂停", pausePI).build())
                        .addAction(new Notification.Action.Builder(
                                android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPI).build())
                        .setStyle(ps)
                        .build();

                mNotifMgr.notify(ID_STOPWATCH, n);
                return;
            } catch (Exception e) {
                Log.w(TAG, "stopwatch live: " + e.getMessage());
            }
        }

        mNotifMgr.notify(ID_STOPWATCH,
                new NotificationCompat.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle("⏱ 秒表")
                        .setContentText(text)
                        .setOngoing(true)
                        .addAction(android.R.drawable.ic_media_pause, "暂停", pausePI)
                        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPI)
                        .build());
    }

    public void pauseStopwatch() {
        mStopwatchOn = false;
        if (mStopwatchTick != null) mHandler.removeCallbacks(mStopwatchTick);
        long el = SystemClock.elapsedRealtime() - mStopwatchBase;

        mNotifMgr.notify(ID_STOPWATCH,
                new NotificationCompat.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_pause)
                        .setContentTitle("⏸ 秒表暂停")
                        .setContentText(fmtSW(el))
                        .setOngoing(true).setAutoCancel(true).build());
    }

    public void stopStopwatch() {
        mStopwatchOn = false;
        if (mStopwatchTick != null) mHandler.removeCallbacks(mStopwatchTick);
        mNotifMgr.cancel(ID_STOPWATCH);
    }

    public boolean isStopwatchRunning() { return mStopwatchOn; }

    // ================================================================
    //  内部工具
    // ================================================================

    /** 从午夜到现在的进度 (0 ~ DAY_PROGRESS_MAX) */
    private int getDayProgress() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int sec = c.get(java.util.Calendar.HOUR_OF_DAY) * 3600
                + c.get(java.util.Calendar.MINUTE) * 60
                + c.get(java.util.Calendar.SECOND);
        return sec * DAY_PROGRESS_MAX / 86400;
    }

    private String fmtSW(long ms) {
        long m = ms / 60000;
        long s = (ms % 60000) / 1000;
        long c = (ms % 1000) / 10;
        return String.format("%02d:%02d.%02d", m, s, c);
    }

    private String srcName(String src) {
        if ("taobao".equals(src))  return "淘宝时间";
        if ("meituan".equals(src)) return "美团时间";
        return "本地时间";
    }

    private PendingIntent mainPI(Context ctx, int req) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent svcPI(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, FloatTimeService.class);
        i.setAction(action);
        return PendingIntent.getService(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 尝试调用 requestPromotedOngoing 提升为 Live Update。
     * 该 API 在 Android 16 QPR1 Beta 2+ 引入，可能尚未稳定。
     * 失败时静默忽略，核心 ProgressStyle 功能仍可用。
     */
    private void tryPromote(Context ctx, Notification n) {
        if (Build.VERSION.SDK_INT < 36 || mNotifMgr == null) return;
        try {
            Method m = mNotifMgr.getClass().getMethod("requestPromotedOngoing", Notification.class);
            m.invoke(mNotifMgr, n);
            Log.d(TAG, "requestPromotedOngoing 调用成功");
        } catch (NoSuchMethodException e) {
            // API 可能还不稳定，尝试其他签名
            try {
                Method m2 = mNotifMgr.getClass().getMethod("requestPromotedOngoing",
                        int.class, Notification.class);
                m2.invoke(mNotifMgr, 0, n);
                Log.d(TAG, "requestPromotedOngoing(id,notif) 调用成功");
            } catch (Exception e2) {
                Log.d(TAG, "requestPromotedOngoing 不可用: " + e2.getMessage());
            }
        } catch (Exception e) {
            Log.d(TAG, "tryPromote 失败: " + e.getMessage());
        }
    }
}
