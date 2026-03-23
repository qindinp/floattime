package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Android 16 Live Updates 管理器
 *
 * 使用 android.app.liveupdates API 实现:
 * - TimerTemplate: 秒表实时计时
 * - ProgressTemplate: 时间同步进度
 * - OngoingActivity: 状态胶囊展示
 *
 * 兼容 API < 36: 回退到普通 Notification
 */
public class LiveUpdateManager {

    private static final String TAG = "LiveUpdateManager";

    // ===== 渠道 =====
    private static final String CHANNEL_ID = "float_time_live_updates";
    private static final String CHANNEL_NAME = "实时活动";
    private static final String CHANNEL_DESC = "显示时间同步、秒表等实时状态";

    // ===== 通知 ID =====
    public static final int ID_TIME_SYNC  = 3001;
    public static final int ID_STOPWATCH  = 3002;
    public static final int ID_COUNTDOWN  = 3003;

    // ===== Live Update 会话 ID =====
    private static final int SESSION_SYNC      = 100;
    private static final int SESSION_STOPWATCH = 101;

    // ===== 依赖 =====
    private final Context mContext;
    private final NotificationManager mNotifMgr;
    private final Handler mHandler;

    // ===== Android 16 Live Updates 实例 =====
    private android.app.LiveUpdateManager mLiveUpdateMgr;

    // ===== 秒表 =====
    private long mStopwatchBaseElapsed = 0;
    private boolean mStopwatchRunning  = false;
    private Runnable mStopwatchTick;
    private int mStopwatchUpdateCount = 0;

    // ===== 构造 =====
    public LiveUpdateManager(Context context) {
        mContext  = context.getApplicationContext();
        mNotifMgr = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mHandler  = new Handler(Looper.getMainLooper());

        createNotificationChannel();

        // Android 16+: 获取 LiveUpdateManager 系统服务
        if (isSupported()) {
            try {
                mLiveUpdateMgr = mContext.getSystemService(android.app.LiveUpdateManager.class);
            } catch (Exception e) {
                Log.w(TAG, "无法获取 LiveUpdateManager: " + e.getMessage());
            }
        }

        Log.d(TAG, "初始化完成 | API=" + Build.VERSION.SDK_INT
                + " | LiveUpdate=" + (mLiveUpdateMgr != null));
    }

    // ================================================================
    //  通知渠道
    // ================================================================

    private void createNotificationChannel() {
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

        if (mNotifMgr != null) mNotifMgr.createNotificationChannel(ch);
    }

    // ================================================================
    //  公共接口
    // ================================================================

    /** 是否支持 Android 16 Live Updates */
    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= 36;
    }

    /** 是否能发布 Live Updates */
    public boolean canPost() {
        return mNotifMgr != null && mNotifMgr.areNotificationsEnabled();
    }

    /** 清除所有 */
    public void clearAll() {
        stopStopwatch();
        cancel(ID_TIME_SYNC);
        cancel(ID_COUNTDOWN);
    }

    // ================================================================
    //  ⏱ 时钟实时通知 (主通知 — 前台服务用)
    // ================================================================

    /**
     * 为前台服务创建时钟通知。
     * API 36+: 使用 Live Updates (OngoingActivity + TimerTemplate)
     * API <36:  普通 Notification
     */
    public Notification createClockNotification(Service service,
                                                  String timeStr,
                                                  String millisStr,
                                                  String sourceName,
                                                  boolean isNight) {
        if (isSupported() && mLiveUpdateMgr != null) {
            return createClockNotificationLive(service, timeStr, millisStr, sourceName, isNight);
        }
        return createClockNotificationLegacy(service, timeStr, millisStr, sourceName);
    }

    /** API < 36: 普通通知 */
    private Notification createClockNotificationLegacy(Service service,
                                                        String timeStr,
                                                        String millisStr,
                                                        String sourceName) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("🕐 " + timeStr + millisStr)
                .setContentText(sourceName + " · 点击打开")
                .setContentIntent(mainIntent(service, 99))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        return b.build();
    }

    /** API 36+: Live Updates 时钟通知 */
    private Notification createClockNotificationLive(Service service,
                                                      String timeStr,
                                                      String millisStr,
                                                      String sourceName,
                                                      boolean isNight) {
        try {
            // 构建标准通知
            Notification notif = new Notification.Builder(service, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("🕐 " + timeStr + millisStr)
                    .setContentText(sourceName)
                    .setContentIntent(mainIntent(service, 99))
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build();

            // 从今天 00:00 开始计时 → 显示当前时间 (HH:mm:ss)
            long todayMidnight = getTodayMidnight();
            long elapsedSec = (System.currentTimeMillis() - todayMidnight) / 1000;

            // TimerTemplate
            android.app.liveupdates.TimerTemplate template =
                    new android.app.liveupdates.TimerTemplate(
                            new android.app.liveupdates.TimerTemplate.TimerConfig(
                                    new android.app.liveupdates.TimerTemplate.Chronometer(
                                            elapsedSec,
                                            /* countDown */ false
                                    )
                            )
                    );

            // OngoingActivity
            android.app.liveupdates.OngoingActivity oa =
                    new android.app.liveupdates.OngoingActivity.Builder(
                            notif,
                            new android.app.liveupdates.LiveUpdateData(
                                    SESSION_SYNC, CHANNEL_ID, template
                            )
                    )
                    .setStatus(new android.app.liveupdates.Status(
                            android.app.liveupdates.Status.STATUS_ACTIVE,
                            sourceName
                    ))
                    .setStaticChip(service.getApplicationInfo().icon)
                    .build();

            oa.publish(service, SESSION_SYNC);
            return notif;

        } catch (Exception e) {
            Log.e(TAG, "createClockNotificationLive 失败: " + e.getMessage());
            return createClockNotificationLegacy(service, timeStr, millisStr, sourceName);
        }
    }

    /** 更新时钟的 Live Update (每秒调用) */
    public void updateClock(Service service, String timeStr, String millisStr, String sourceName) {
        if (!isSupported() || mLiveUpdateMgr == null) return;
        try {
            long todayMidnight = getTodayMidnight();
            long elapsedSec = (System.currentTimeMillis() - todayMidnight) / 1000;

            android.app.liveupdates.TimerTemplate template =
                    new android.app.liveupdates.TimerTemplate(
                            new android.app.liveupdates.TimerTemplate.TimerConfig(
                                    new android.app.liveupdates.TimerTemplate.Chronometer(
                                            elapsedSec, false
                                    )
                            )
                    );

            Notification notif = new Notification.Builder(service, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("🕐 " + timeStr + millisStr)
                    .setContentText(sourceName)
                    .setContentIntent(mainIntent(service, 99))
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build();

            android.app.liveupdates.LiveUpdateData data =
                    new android.app.liveupdates.LiveUpdateData(
                            SESSION_SYNC, CHANNEL_ID, template
                    );

            mLiveUpdateMgr.update(data, notif);
        } catch (Exception e) {
            Log.w(TAG, "updateClock: " + e.getMessage());
        }
    }

    // ================================================================
    //  🔄 时间同步 Live Updates
    // ================================================================

    /** 同步中 */
    public void showTimeSyncing(String source) {
        String name = srcName(source);

        if (isSupported() && mLiveUpdateMgr != null) {
            try {
                android.app.liveupdates.ProgressTracker tracker =
                        new android.app.liveupdates.ProgressTracker();
                tracker.setIndeterminate(true);

                Notification notif = new Notification.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_popup_sync)
                        .setContentTitle("同步中")
                        .setContentText("从 " + name + " 获取时间…")
                        .setContentIntent(mainIntent(mContext, ID_TIME_SYNC))
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setProgress(0, 0, true)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build();

                android.app.liveupdates.ProgressTemplate tpl =
                        new android.app.liveupdates.ProgressTemplate(tracker);

                android.app.liveupdates.LiveUpdateData data =
                        new android.app.liveupdates.LiveUpdateData(
                                SESSION_SYNC, CHANNEL_ID, tpl
                        );

                android.app.liveupdates.OngoingActivity oa =
                        new android.app.liveupdates.OngoingActivity.Builder(notif, data)
                                .setStatus(new android.app.liveupdates.Status(
                                        android.app.liveupdates.Status.STATUS_ACTIVE,
                                        "同步中"))
                                .build();

                oa.publish(mContext, SESSION_SYNC);
                return;
            } catch (Exception e) {
                Log.w(TAG, "showTimeSyncing: " + e.getMessage());
            }
        }

        // 回退
        Notification n = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("同步中")
                .setContentText("从 " + name + " 获取时间…")
                .setOngoing(false).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(0, 0, true)
                .build();
        notify(ID_TIME_SYNC, n);
    }

    /** 同步成功 */
    public void showTimeSyncSuccess(String source, long offsetMs) {
        String name  = srcName(source);
        String sign  = offsetMs >= 0 ? "+" : "";
        String label = name + " 偏差 " + sign + offsetMs + "ms";

        if (isSupported() && mLiveUpdateMgr != null) {
            try {
                android.app.liveupdates.ProgressTracker tracker =
                        new android.app.liveupdates.ProgressTracker();
                tracker.setProgress(100);

                Notification notif = new Notification.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_input_get)
                        .setContentTitle("✅ 时间已校准")
                        .setContentText(label)
                        .setAutoCancel(true)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build();

                android.app.liveupdates.ProgressTemplate tpl =
                        new android.app.liveupdates.ProgressTemplate(tracker);

                android.app.liveupdates.LiveUpdateData data =
                        new android.app.liveupdates.LiveUpdateData(
                                SESSION_SYNC, CHANNEL_ID, tpl
                        );

                android.app.liveupdates.OngoingActivity oa =
                        new android.app.liveupdates.OngoingActivity.Builder(notif, data)
                                .setStatus(new android.app.liveupdates.Status(
                                        android.app.liveupdates.Status.STATUS_RESOLVED,
                                        "已校准"))
                                .build();

                oa.publish(mContext, SESSION_SYNC);
            } catch (Exception e) {
                Log.w(TAG, "showTimeSyncSuccess: " + e.getMessage());
            }
        } else {
            Notification n = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_input_get)
                    .setContentTitle("✅ 时间已校准")
                    .setContentText(label)
                    .setAutoCancel(true).build();
            notify(ID_TIME_SYNC, n);
        }

        mHandler.postDelayed(() -> cancel(ID_TIME_SYNC), 3000);
    }

    /** 同步失败 */
    public void showTimeSyncFailed(String source) {
        String name = srcName(source);

        if (isSupported() && mLiveUpdateMgr != null) {
            try {
                android.app.liveupdates.ProgressTracker tracker =
                        new android.app.liveupdates.ProgressTracker();
                tracker.setProgress(0);

                Notification notif = new Notification.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle("❌ 同步失败")
                        .setContentText("无法连接 " + name + "，已用本地时间")
                        .setAutoCancel(true)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build();

                android.app.liveupdates.LiveUpdateData data =
                        new android.app.liveupdates.LiveUpdateData(
                                SESSION_SYNC, CHANNEL_ID,
                                new android.app.liveupdates.ProgressTemplate(tracker)
                        );

                android.app.liveupdates.OngoingActivity oa =
                        new android.app.liveupdates.OngoingActivity.Builder(notif, data)
                                .setStatus(new android.app.liveupdates.Status(
                                        android.app.liveupdates.Status.STATUS_RESOLVED,
                                        "失败"))
                                .build();

                oa.publish(mContext, SESSION_SYNC);
            } catch (Exception e) {
                Log.w(TAG, "showTimeSyncFailed: " + e.getMessage());
            }
        } else {
            Notification n = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("❌ 同步失败")
                    .setContentText("无法连接 " + name)
                    .setAutoCancel(true).build();
            notify(ID_TIME_SYNC, n);
        }

        mHandler.postDelayed(() -> cancel(ID_TIME_SYNC), 5000);
    }

    // ================================================================
    //  ⏱ 秒表 Live Updates
    // ================================================================

    public void startStopwatch() {
        if (mStopwatchRunning) return;
        mStopwatchRunning   = true;
        mStopwatchUpdateCount = 0;
        mStopwatchBaseElapsed = SystemClock.elapsedRealtime();

        PendingIntent pausePI = svcIntent(mContext, "STOPWATCH_PAUSE", 10);
        PendingIntent stopPI  = svcIntent(mContext, "STOPWATCH_STOP",  11);

        mStopwatchTick = new Runnable() {
            @Override public void run() {
                if (!mStopwatchRunning) return;
                long elapsed = SystemClock.elapsedRealtime() - mStopwatchBaseElapsed;
                updateStopwatchNotification(elapsed, pausePI, stopPI);
                mHandler.postDelayed(this, 100);
            }
        };
        mHandler.post(mStopwatchTick);
    }

    private void updateStopwatchNotification(long elapsedMs,
                                              PendingIntent pausePI,
                                              PendingIntent stopPI) {
        String timeFmt = fmtStopwatch(elapsedMs);
        mStopwatchUpdateCount++;

        if (isSupported() && mLiveUpdateMgr != null) {
            try {
                long elapsedSec = elapsedMs / 1000;

                Notification notif = new Notification.Builder(mContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle("⏱ 秒表")
                        .setContentText(timeFmt)
                        .setOngoing(true)
                        .addAction(new Notification.Action.Builder(
                                android.R.drawable.ic_media_pause, "暂停", pausePI).build())
                        .addAction(new Notification.Action.Builder(
                                android.R.drawable.ic_media_stop, "停止", stopPI).build())
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build();

                android.app.liveupdates.TimerTemplate tpl =
                        new android.app.liveupdates.TimerTemplate(
                                new android.app.liveupdates.TimerTemplate.TimerConfig(
                                        new android.app.liveupdates.TimerTemplate.Chronometer(
                                                elapsedSec, false
                                        )
                                )
                        );

                android.app.liveupdates.LiveUpdateData data =
                        new android.app.liveupdates.LiveUpdateData(
                                SESSION_STOPWATCH, CHANNEL_ID, tpl
                        );

                // 只有第 1 次和每 10 次才 publish OngoingActivity
                if (mStopwatchUpdateCount <= 1 || mStopwatchUpdateCount % 10 == 0) {
                    android.app.liveupdates.OngoingActivity oa =
                            new android.app.liveupdates.OngoingActivity.Builder(notif, data)
                                    .setStatus(new android.app.liveupdates.Status(
                                            android.app.liveupdates.Status.STATUS_ACTIVE,
                                            timeFmt))
                                    .build();
                    oa.publish(mContext, SESSION_STOPWATCH);
                } else {
                    mLiveUpdateMgr.update(data, notif);
                }
                return;
            } catch (Exception e) {
                Log.w(TAG, "stopwatch live update: " + e.getMessage());
            }
        }

        // 回退
        Notification n = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("⏱ 秒表")
                .setContentText(timeFmt)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "暂停", pausePI)
                .addAction(android.R.drawable.ic_media_stop, "停止", stopPI)
                .build();
        notify(ID_STOPWATCH, n);
    }

    public void pauseStopwatch() {
        mStopwatchRunning = false;
        if (mStopwatchTick != null) mHandler.removeCallbacks(mStopwatchTick);

        long elapsed = SystemClock.elapsedRealtime() - mStopwatchBaseElapsed;

        Notification n = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle("⏸ 秒表暂停")
                .setContentText(fmtStopwatch(elapsed))
                .setOngoing(true)
                .setAutoCancel(true).build();
        notify(ID_STOPWATCH, n);
    }

    public void stopStopwatch() {
        mStopwatchRunning = false;
        if (mStopwatchTick != null) mHandler.removeCallbacks(mStopwatchTick);
        cancel(ID_STOPWATCH);

        if (isSupported() && mLiveUpdateMgr != null) {
            try {
                mLiveUpdateMgr.endSession(SESSION_STOPWATCH);
            } catch (Exception ignored) {}
        }
    }

    public boolean isStopwatchRunning() {
        return mStopwatchRunning;
    }

    // ================================================================
    //  内部工具
    // ================================================================

    private long getTodayMidnight() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private String fmtStopwatch(long ms) {
        long min = ms / 60000;
        long sec = (ms % 60000) / 1000;
        long cs  = (ms % 1000) / 10;
        return String.format("%02d:%02d.%02d", min, sec, cs);
    }

    private String srcName(String source) {
        if ("taobao".equals(source))  return "淘宝时间";
        if ("meituan".equals(source)) return "美团时间";
        return "本地时间";
    }

    private PendingIntent mainIntent(Context ctx, int reqCode) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent svcIntent(Context ctx, String action, int reqCode) {
        Intent i = new Intent(ctx, FloatTimeService.class);
        i.setAction(action);
        return PendingIntent.getService(ctx, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void notify(int id, Notification n) {
        if (mNotifMgr != null && n != null) {
            try { mNotifMgr.notify(id, n); }
            catch (Exception e) { Log.e(TAG, "notify(" + id + "): " + e.getMessage()); }
        }
    }

    private void cancel(int id) {
        if (mNotifMgr != null) mNotifMgr.cancel(id);
    }
}
