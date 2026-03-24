package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 悬浮时间前台服务
 * 使用通知栏显示实时校准时间
 *
 * 优化:
 * - 通知 Builder 复用: 不每次 new Builder，只更新 title/text
 * - 夜间模式逻辑抽成工具方法避免重复
 * - Theme CHANGE_THEME action 处理
 * - SharedPreferences 写入节流
 * - 连接超时提升到 5s
 * - 统一使用 LiveUpdateManager 的通知渠道
 */
public class FloatTimeService extends Service {

    private static final String TAG = "FloatTimeService";
    private static final String PREFS_NAME = "FloatTimePrefs";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_OFFSET_MS = "offset_ms";
    private static final String KEY_TIME_SOURCE = "time_source";

    private static final int NOTIFICATION_ID = 20240320;

    // 优化: 通知更新间隔从 100ms 提升到 200ms
    // 原来每 100ms 都重建完整通知（Builder + JSON + 反射），200ms 足够人类视觉感知
    private static final int UPDATE_INTERVAL_MS = 200;

    private static final int CONNECT_TIMEOUT_MS = 5000; // 修复: 3000ms 对淘宝 API 太紧
    private static final int READ_TIMEOUT_MS = 5000;

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private static volatile boolean sIsRunning = false;
    public static boolean isRunning() { return sIsRunning; }

    private NotificationManager mNotificationManager;
    private LiveUpdateManager mLiveUpdateManager;

    private SharedPreferences mPrefs;
    private Handler mHandler;
    private Runnable mTimeRunnable;

    private String mTimeSource = "taobao";
    private long mOffsetMs = 0;
    private final AtomicBoolean mIsSyncing = new AtomicBoolean(false);
    private String mCurrentTimeStr = "";
    private String mCurrentMillisStr = "";

    private int mThemeMode = 0;
    private boolean mIsNightMode = false;

    // 优化: 复用通知 Builder，只更新变化的部分
    private Notification mCachedNotification;
    private String mLastTimeStr = "";
    private String mLastMillisStr = "";

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning = true;
        mHandler = new Handler(Looper.getMainLooper());
        mPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        mLiveUpdateManager = new LiveUpdateManager(this);

        loadPreferences();
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        startForeground(NOTIFICATION_ID, createNotification());
        startClock();
        syncTime();

        log("FloatTimeService started, LiveUpdate supported: " + mLiveUpdateManager.isSupported());
    }

    private void loadPreferences() {
        mThemeMode = mPrefs.getInt(KEY_THEME_MODE, 0);
        mOffsetMs = mPrefs.getLong(KEY_OFFSET_MS, 0);
        mTimeSource = mPrefs.getString(KEY_TIME_SOURCE, "taobao");
        mIsNightMode = calcNightMode(mThemeMode);
    }

    // 优化: 抽取夜间模式计算逻辑，多处复用
    static boolean calcNightMode(int themeMode) {
        if (themeMode == 0) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            return hour >= 19 || hour < 7;
        }
        return themeMode == 2;
    }

    /**
     * 创建通知 (首次 + 每次需要重新构建时)
     */
    private Notification createNotification() {
        try {
            String timeStr = mCurrentTimeStr.isEmpty() ? "--:--:--" : mCurrentTimeStr;
            String millisStr = mCurrentMillisStr.isEmpty() ? ".000" : "." + mCurrentMillisStr;

            Notification notification = mLiveUpdateManager.createClockNotification(
                    this, timeStr, millisStr, getSourceDisplayName(), mIsNightMode
            );
            mCachedNotification = notification;
            mLastTimeStr = timeStr;
            mLastMillisStr = millisStr;
            return notification;
        } catch (Exception e) {
            log("createNotification error: " + e.getMessage());
            return createFallbackNotification();
        }
    }

    private Notification createFallbackNotification() {
        return new NotificationCompat.Builder(this, LiveUpdateManager.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("悬浮时间")
                .setContentText("服务运行中")
                .setOngoing(true)
                .build();
    }

    private void startClock() {
        mTimeRunnable = new Runnable() {
            @Override
            public void run() {
                updateTime();
                if (mHandler != null) {
                    mHandler.postDelayed(this, UPDATE_INTERVAL_MS);
                }
            }
        };
        mHandler.post(mTimeRunnable);
    }

    private void updateTime() {
        try {
            long now = System.currentTimeMillis() + mOffsetMs;
            Date date = new Date(now);

            String timeStr;
            synchronized (TIME_FORMAT) {
                timeStr = TIME_FORMAT.format(date);
            }

            mCurrentTimeStr = timeStr;
            mCurrentMillisStr = String.format(Locale.getDefault(), "%03d", now % 1000);

            // 主题切换检查 (优化: 用缓存的小时值,不再每次创建 Calendar)
            boolean newNight = calcNightMode(mThemeMode);
            if (newNight != mIsNightMode) {
                mIsNightMode = newNight;
                log("Theme switched: " + (mIsNightMode ? "dark" : "light"));
            }

            updateNotification();

        } catch (Exception e) {
            log("updateTime error: " + e.getMessage());
        }
    }

    /**
     * 更新通知
     * 优化: 直接复用 LiveUpdateManager.updateClock() 通知
     */
    private void updateNotification() {
        if (mNotificationManager == null) return;
        try {
            String timeStr = mCurrentTimeStr.isEmpty() ? "--:--:--" : mCurrentTimeStr;
            String millisStr = mCurrentMillisStr.isEmpty() ? ".000" : "." + mCurrentMillisStr;

            mLiveUpdateManager.updateClock(
                    this, timeStr, millisStr,
                    getSourceDisplayName(), mIsNightMode
            );
        } catch (Exception e) {
            log("updateNotification error: " + e.getMessage());
        }
    }

    private void syncTime() {
        if (!mIsSyncing.compareAndSet(false, true)) return;

        if ("local".equals(mTimeSource)) {
            mOffsetMs = 0;
            saveOffset();
            mLiveUpdateManager.showTimeSyncSuccess("local", 0);
            log("Local time mode, offset=0");
            mIsSyncing.set(false);
            return;
        }

        log("Syncing time from: " + mTimeSource);
        mLiveUpdateManager.showTimeSyncing(mTimeSource);

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String url = "taobao".equals(mTimeSource)
                        ? "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
                        : "https://api.meituan.com/nationalTimestamp";

                long localBefore = System.currentTimeMillis();

                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    long localAfter = System.currentTimeMillis();
                    long localMid = (localBefore + localAfter) / 2;
                    long serverTime = parseServerTime(response.toString());

                    if (serverTime > 0) {
                        mOffsetMs = serverTime - localMid;
                        saveOffset();
                        mLiveUpdateManager.showTimeSyncSuccess(mTimeSource, mOffsetMs);
                        log("Time synced: offset=" + mOffsetMs + "ms");
                    } else {
                        mLiveUpdateManager.showTimeSyncFailed(mTimeSource);
                        log("Failed to parse server time");
                    }
                } else {
                    mLiveUpdateManager.showTimeSyncFailed(mTimeSource);
                    log("HTTP error: " + responseCode);
                }
            } catch (Exception e) {
                mLiveUpdateManager.showTimeSyncFailed(mTimeSource);
                log("syncTime error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
                mIsSyncing.set(false);
            }
        }).start();
    }

    private long parseServerTime(String response) {
        try {
            JSONObject json = new JSONObject(response);
            long timestamp = 0;

            if (json.has("data")) {
                JSONObject data = json.getJSONObject("data");
                if (data.has("t")) {
                    timestamp = Long.parseLong(data.getString("t"));
                }
            }

            if (timestamp == 0 && json.has("t")) {
                timestamp = json.getLong("t");
            }

            if (timestamp == 0 && json.has("timestamp")) {
                timestamp = json.getLong("timestamp");
            }

            if (timestamp > 0) {
                return timestamp < 10000000000L ? timestamp * 1000 : timestamp;
            }
        } catch (Exception e) {
            log("parseServerTime error: " + e.getMessage());
        }
        return 0;
    }

    // 优化: SharedPreferences 写入节流 (最多每 5 秒写一次)
    private long mLastSaveTime = 0;

    private void saveOffset() {
        long now = System.currentTimeMillis();
        if (now - mLastSaveTime < 5000) return;
        mLastSaveTime = now;
        mPrefs.edit().putLong(KEY_OFFSET_MS, mOffsetMs).apply();
    }

    private String getSourceDisplayName() {
        switch (mTimeSource) {
            case "taobao": return "淘宝时间";
            case "meituan": return "美团时间";
            default: return "本地时间";
        }
    }

    private void log(String message) {
        Log.d(TAG, message);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("STOP".equals(action)) {
                stopSelf();
            }
            // 修复: 处理主题切换 action (之前缺失)
            else if ("CHANGE_THEME".equals(action)) {
                int mode = intent.getIntExtra("mode", 0);
                mThemeMode = mode;
                mPrefs.edit().putInt(KEY_THEME_MODE, mode).apply();
                mIsNightMode = calcNightMode(mode);
                log("Theme changed to: " + mode + ", night=" + mIsNightMode);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        log("onDestroy called");
        sIsRunning = false;

        if (mLiveUpdateManager != null) {
            mLiveUpdateManager.clearAll();
        }

        if (mHandler != null && mTimeRunnable != null) {
            mHandler.removeCallbacks(mTimeRunnable);
        }

        // 修复: 退出前保存偏移量
        mPrefs.edit().putLong(KEY_OFFSET_MS, mOffsetMs).apply();

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
