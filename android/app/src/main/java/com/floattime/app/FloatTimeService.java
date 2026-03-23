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
 */
public class FloatTimeService extends Service {

    private static final String TAG = "FloatTimeService";
    private static final String PREFS_NAME = "FloatTimePrefs";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_OFFSET_MS = "offset_ms";
    private static final String KEY_TIME_SOURCE = "time_source";

    private static final String CHANNEL_ID = "float_time_channel";
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final int NOTIFICATION_ID = 20240320;

    private static final int UPDATE_INTERVAL_MS = 100;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    
    private static volatile boolean sIsRunning = false;
    public static boolean isRunning() { return sIsRunning; }

    private NotificationManager mNotificationManager;
    private LiveUpdateManager mLiveUpdateManager;
    private SuperIslandManager mSuperIslandManager;

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

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning = true;
        mHandler = new Handler(Looper.getMainLooper());
        mPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        mLiveUpdateManager = new LiveUpdateManager(this);
        mSuperIslandManager = new SuperIslandManager(this);
        
        log("Super Island supported: " + mSuperIslandManager.isSupported());
        
        loadPreferences();
        createNotificationChannel();
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
        updateNightMode();
    }

    private void updateNightMode() {
        if (mThemeMode == 0) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            mIsNightMode = hour >= 19 || hour < 7;
        } else {
            mIsNightMode = mThemeMode == 2;
        }
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("显示实时校准时间");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.enableLights(false);
            channel.setSound(null, null);
            
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        try {
            String timeStr = mCurrentTimeStr.isEmpty() ? "--:--:--" : mCurrentTimeStr;
            String millisStr = mCurrentMillisStr.isEmpty() ? ".000" : "." + mCurrentMillisStr.substring(0, Math.min(3, mCurrentMillisStr.length()));
            
            // ✅ 修复: 使用 LiveUpdateManager 返回的通知（包含小米 Live Update extras）
            // 之前这里创建了通知但丢弃了返回值，直接造了个新的——导致超级岛 extras 失效
            return mLiveUpdateManager.createClockNotification(
                this, timeStr, millisStr, getSourceDisplayName(), mIsNightMode
            );
            
        } catch (Exception e) {
            log("createNotification error: " + e.getMessage());
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("悬浮时间")
                .setContentText("服务运行中")
                .setOngoing(true)
                .build();
        }
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
            
            // 每100ms更新一次通知
            if (now % 100 < 50) {
                updateNotification();
            }
            
            // 检查主题切换
            if (mThemeMode == 0) {
                boolean newNight = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 19 || 
                                   java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 7;
                if (newNight != mIsNightMode) {
                    mIsNightMode = newNight;
                    log("Theme switched: " + (mIsNightMode ? "dark" : "light"));
                }
            }
            
        } catch (Exception e) {
            log("updateTime error: " + e.getMessage());
        }
    }

    private void updateNotification() {
        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }
        if (mNotificationManager != null) {
            try {
                // 更新前台通知
                mNotificationManager.notify(NOTIFICATION_ID, createNotification());
                
                // 更新超级岛
                if (mSuperIslandManager != null && mSuperIslandManager.isSupported()) {
                    mSuperIslandManager.update(
                        mCurrentTimeStr,
                        "." + mCurrentMillisStr.substring(0, Math.min(3, mCurrentMillisStr.length())),
                        getSourceDisplayName()
                    );
                }
            } catch (Exception e) {
                log("updateNotification error: " + e.getMessage());
            }
        }
    }

    private void syncTime() {
        if (!mIsSyncing.compareAndSet(false, true)) {
            return;
        }
        
        if ("local".equals(mTimeSource)) {
            mOffsetMs = 0;
            mPrefs.edit().putLong(KEY_OFFSET_MS, 0).apply();
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
                        new java.io.InputStreamReader(conn.getInputStream())
                    );
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
                        mPrefs.edit().putLong(KEY_OFFSET_MS, mOffsetMs).apply();
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

    private String getSourceDisplayName() {
        return "taobao".equals(mTimeSource) ? "淘宝时间" : 
               "meituan".equals(mTimeSource) ? "美团时间" : "本地时间";
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
        
        if (mSuperIslandManager != null) {
            mSuperIslandManager.destroy();
        }
        
        if (mHandler != null && mTimeRunnable != null) {
            mHandler.removeCallbacks(mTimeRunnable);
        }
        
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
