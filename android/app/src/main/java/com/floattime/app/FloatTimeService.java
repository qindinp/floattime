package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 悬浮时间前台服务 - 支持日夜间模式、圆角磨砂效果、Android 16 Live Updates
 */
public class FloatTimeService extends Service {

    private static final String TAG = "FloatTimeService";
    private static final String PREFS_NAME = "FloatTimePrefs";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_OFFSET_MS = "offset_ms";
    private static final String KEY_TIME_SOURCE = "time_source";
    private static final String KEY_FLOAT_X = "float_x";
    private static final String KEY_FLOAT_Y = "float_y";

    private static final String CHANNEL_ID = "float_time_channel";
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final int NOTIFICATION_ID = 20240320;
    private static final int LOG_FILE_MAX_LINES = 100;
    
    // ✅ 修复: 提取魔法数字为常量
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int UPDATE_INTERVAL_MS = 50;
    private static final int LOG_FILE_MAX_SIZE = 100 * 1024;  // 100KB
    
    // ✅ 修复: 时间显示线程安全
    private static final SimpleDateFormat TIME_FORMAT = 
        new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private static final AtomicBoolean sIsRunning = new AtomicBoolean(false);
    public static boolean isRunning() {
        return sIsRunning.get();
    }

    private WindowManager mWindowManager;
    private NotificationManager mNotificationManager;
    private View mFloatView;
    private WindowManager.LayoutParams mFloatParams;
    
    // Android 16 Live Updates 管理器
    private LiveUpdateManager mLiveUpdateManager;
    
    private TextView mTimeText;
    private TextView mMillisText;
    private TextView mDateText;
    private TextView mSourceText;
    private View mSyncDot;
    
    private Handler mHandler;
    private Runnable mTimeRunnable;
    private String mTimeSource = "taobao";
    private long mOffsetMs = 0;
    private final AtomicBoolean mIsSyncing = new AtomicBoolean(false);  // ✅ 修复: 使用 AtomicBoolean
    private String mCurrentTimeStr = "";
    private String mCurrentMillisStr = "";
    
    private int mThemeMode = 0; // 0=auto, 1=light, 2=dark
    private boolean mIsNightMode = false;
    private SharedPreferences mPrefs;

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning.set(true);
        mHandler = new Handler(Looper.getMainLooper());
        mPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // 初始化 Live Update 管理器
        mLiveUpdateManager = new LiveUpdateManager(this);
        
        log("FloatTimeService onCreate, LiveUpdate supported: " + mLiveUpdateManager.isLiveUpdateSupported());
        
        try {
            loadPreferences();
            createNotificationChannel();
            
            // ✅ 先初始化变量
            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            
            // ✅ 创建通知（先于悬浮窗）
            try {
                startForeground(NOTIFICATION_ID, createNotification());
            } catch (Exception e) {
                log("Failed to create notification: " + e.getMessage());
            }
            
            // ✅ 启动时钟和同步（即使悬浮窗失败也要运行）
            startClock();
            syncTime();
            
            // ✅ 检查是否启用悬浮窗，最后创建
            boolean enableFloatingWindow = mPrefs.getBoolean("float_window_enabled", true);
            if (mWindowManager != null && enableFloatingWindow) {
                try {
                    createFloatingView();
                } catch (Exception e) {
                    log("Failed to create floating view: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            log("onCreate critical error: " + e.getMessage());
            e.printStackTrace();
            // ✅ 即使失败也要继续运行，不停止服务
        }
    }

    private void loadPreferences() {
        mThemeMode = mPrefs.getInt(KEY_THEME_MODE, 0);
        mOffsetMs = mPrefs.getLong(KEY_OFFSET_MS, 0);
        mTimeSource = mPrefs.getString(KEY_TIME_SOURCE, "taobao");
        
        // 根据主题模式确定是否夜间
        updateNightMode();
        log("Loaded preferences: themeMode=" + mThemeMode + ", source=" + mTimeSource + ", isNight=" + mIsNightMode);
    }

    private void updateNightMode() {
        if (mThemeMode == 0) {
            // 自动模式：跟随系统时间
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            mIsNightMode = (hour >= 19 || hour < 7);
        } else {
            mIsNightMode = (mThemeMode == 2);
        }
        log("Theme updated: mode=" + mThemeMode + ", isNight=" + mIsNightMode);
    }

    public void setThemeMode(int mode) {
        mThemeMode = mode;
        mPrefs.edit().putInt(KEY_THEME_MODE, mode).apply();
        updateNightMode();
        updateFloatingStyle();
        log("Theme mode changed to: " + mode);
    }

    public int getThemeMode() {
        return mThemeMode;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log("onStartCommand called");
        
        if (intent != null) {
            String action = intent.getAction();
            if ("CHANGE_THEME".equals(action)) {
                int mode = intent.getIntExtra("mode", 0);
                setThemeMode(mode);
            } else if ("CHANGE_SOURCE".equals(action)) {
                String source = intent.getStringExtra("source");
                if (source != null) {
                    mTimeSource = source;
                    mPrefs.edit().putString(KEY_TIME_SOURCE, source).apply();
                    updateSourceDisplay();
                    syncTime();
                    log("Time source changed to: " + source);
                }
            } else if ("STOPWATCH_START".equals(action)) {
                // 启动秒表 Live Update
                if (mLiveUpdateManager != null) {
                    mLiveUpdateManager.startStopwatch();
                    log("Stopwatch started via Live Update");
                }
            } else if ("STOPWATCH_PAUSE".equals(action)) {
                // 暂停秒表
                if (mLiveUpdateManager != null) {
                    mLiveUpdateManager.pauseStopwatch();
                    log("Stopwatch paused");
                }
            } else if ("STOPWATCH_STOP".equals(action)) {
                // 停止秒表
                if (mLiveUpdateManager != null) {
                    mLiveUpdateManager.stopStopwatch();
                    log("Stopwatch stopped");
                }
            }
        }
        
        return START_STICKY;
    }

    private void updateSourceDisplay() {
        String sourceLabel = getSourceShortName();
        if (mSourceText != null) {
            mSourceText.setText(sourceLabel);
        }
        updateFloatingStyle();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("悬浮时间服务运行中");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        try {
            RemoteViews collapsedView = new RemoteViews(getPackageName(), R.layout.notification_collapsed);
            RemoteViews expandedView = new RemoteViews(getPackageName(), R.layout.notification_expanded);
            
            // ✅ 确保时间字符串不为空
            String timeStr = mCurrentTimeStr.isEmpty() ? "--:--:--" : mCurrentTimeStr;
            String millisStr = mCurrentMillisStr.isEmpty() ? ".000" : "." + mCurrentMillisStr.substring(0, Math.min(3, mCurrentMillisStr.length()));
            
            collapsedView.setTextViewText(R.id.notification_time, timeStr);
            expandedView.setTextViewText(R.id.notification_time, timeStr + millisStr);
            
            String sourceText = getSourceDisplayName();
            collapsedView.setTextViewText(R.id.notification_source, sourceText);
            expandedView.setTextViewText(R.id.notification_source, sourceText);
            
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)  // ✅ 改为 LOW，减少系统干预
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCustomContentView(collapsedView)
                .setCustomBigContentView(expandedView);
            
            // Android 12 及以下使用 DecoratedCustomViewStyle
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                builder.setStyle(new NotificationCompat.DecoratedCustomViewStyle());
            }
            
            // Android 12+ 灵动岛/超级岛支持
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
            }
            
            return builder.build();
        } catch (Exception e) {
            log("createNotification error: " + e.getMessage());
            e.printStackTrace();
            // 返回一个简单的备用通知
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("悬浮时间")
                .setContentText("服务运行中")
                .setOngoing(true)
                .build();
        }
    }

    private void updateNotification() {
        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }
        if (mNotificationManager != null) {
            try {
                Notification notification = createNotification();
                if (notification != null) {
                    mNotificationManager.notify(NOTIFICATION_ID, notification);
                }
            } catch (Exception e) {
                log("updateNotification error: " + e.getMessage());
            }
        }
    }

    private void createFloatingView() {
        log("createFloatingView: starting...");
        
        try {
            // ✅ 检查权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean hasPermission = Settings.canDrawOverlays(this);
                log("Overlay permission: " + hasPermission);
                if (!hasPermission) {
                    Log.w(TAG, "No overlay permission, cannot create floating view");
                    return;
                }
            }
            
            log("Creating floating view...");
            
            // ✅ 使用 application context 避免 BadTokenException
            try {
                mFloatView = LayoutInflater.from(getApplicationContext()).inflate(R.layout.float_ball, null);
                log("Layout inflated: " + (mFloatView != null ? "success" : "null"));
            } catch (Exception e) {
                log("Layout inflate failed: " + e.getMessage());
                e.printStackTrace();
                return;
            }
            
            // ✅ 验证悬浮窗视图是否创建成功
            if (mFloatView == null) {
                Log.e(TAG, "Failed to inflate float_ball layout");
                return;
            }
            
            mTimeText = mFloatView.findViewById(R.id.timeText);
            mMillisText = mFloatView.findViewById(R.id.millisText);
            mDateText = mFloatView.findViewById(R.id.dateText);
            mSourceText = mFloatView.findViewById(R.id.sourceText);
            mSyncDot = mFloatView.findViewById(R.id.syncDot);
            
            updateFloatingStyle();
            
            // ✅ 使用 TYPE_APPLICATION_OVERLAY（需要 SYSTEM_ALERT_WINDOW 权限）
            int layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            
            mFloatParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
            );
            
            mFloatParams.gravity = Gravity.TOP | Gravity.START;
            mFloatParams.x = mPrefs.getInt(KEY_FLOAT_X, 50);
            mFloatParams.y = mPrefs.getInt(KEY_FLOAT_Y, 200);
            mFloatParams.packageName = getPackageName();
            
            setupTouchListener();
            
            // ✅ 尝试添加悬浮窗
            log("WindowManager: " + (mWindowManager != null ? "ok" : "null"));
            log("FloatView: " + (mFloatView != null ? "ok" : "null"));
            log("FloatParams: " + (mFloatParams != null ? "ok" : "null"));
            
            if (mWindowManager != null && mFloatView != null && mFloatParams != null) {
                try {
                    log("Attempting to add view...");
                    mWindowManager.addView(mFloatView, mFloatParams);
                    Log.d(TAG, "✅ Floating view created successfully");
                } catch (WindowManager.BadTokenException e) {
                    // ✅ BadTokenException 不再静默失败，而是延迟重试
                    Log.w(TAG, "BadTokenException, will retry: " + e.getMessage());
                    mHandler.postDelayed(() -> {
                        Log.d(TAG, "Retrying to add floating view...");
                        tryRemoveFloatingView(); // 先移除可能存在的旧视图
                        createFloatingView(); // 重新创建
                    }, 1000);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "IllegalArgumentException: " + e.getMessage());
                    e.printStackTrace();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add floating view: " + e.getMessage());
                    e.printStackTrace();
                    // ✅ 延迟重试
                    mHandler.postDelayed(this::retryCreateFloatingView, 2000);
                }
            } else {
                Log.e(TAG, "Cannot add floating view: some dependencies are null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create floating view: " + e.getMessage(), e);
            e.printStackTrace();
            // ✅ 延迟重试
            mHandler.postDelayed(this::retryCreateFloatingView, 2000);
        }
    }
    
    // ✅ 重试创建悬浮窗
    private void retryCreateFloatingView() {
        if (!mPrefs.getBoolean("float_window_enabled", true)) {
            return; // 用户禁用了悬浮窗
        }
        
        Log.d(TAG, "Retrying to create floating view...");
        tryRemoveFloatingView();
        createFloatingView();
    }
    
    // ✅ 安全移除悬浮窗
    private void tryRemoveFloatingView() {
        if (mFloatView != null && mWindowManager != null) {
            try {
                if (mFloatView.getParent() != null) {
                    mWindowManager.removeView(mFloatView);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove floating view: " + e.getMessage());
            }
        }
    }

    private void updateFloatingStyle() {
        if (mFloatView == null) return;
        
        try {
            // 根据日间/夜间模式设置颜色
            int bgColor;
            int textColor = Color.WHITE;
            float cornerRadius = dpToPx(18); // 18dp 圆角
            
            if (mIsNightMode) {
                // 夜间模式
                bgColor = 0xF01A1A2E; // 深色半透明背景
                if ("taobao".equals(mTimeSource)) {
                    bgColor = 0xF0E04500; // 深色淘宝橙
                } else if ("meituan".equals(mTimeSource)) {
                    bgColor = 0xF0CC8800; // 深色美团黄
                } else {
                    bgColor = 0xF01A3A6E; // 深色蓝色
                }
            } else {
                // 日间模式
                if ("taobao".equals(mTimeSource)) {
                    bgColor = 0xF2FF5722; // 淘宝橙色
                } else if ("meituan".equals(mTimeSource)) {
                    bgColor = 0xF2FFC107; // 美团黄色
                } else {
                    bgColor = 0xF22196F3; // 蓝色
                }
            }
            
            // 创建圆角矩形背景（带磨砂效果）
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(cornerRadius);
            drawable.setColor(bgColor);
            drawable.setAlpha(242); // 约95%透明度
            
            // 设置阴影/elevation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mFloatView.setElevation(dpToPx(8));
            }
            
            mFloatView.setBackground(drawable);
            
            // 设置文字颜色
            if (mTimeText != null) mTimeText.setTextColor(textColor);
            if (mMillisText != null) mMillisText.setTextColor(textColor);
            if (mDateText != null) mDateText.setTextColor(Color.argb(200, 255, 255, 255));
            if (mSourceText != null) mSourceText.setTextColor(textColor);
            
            log("Floating style updated: bgColor=" + Integer.toHexString(bgColor) + ", isNight=" + mIsNightMode);
            
        } catch (Exception e) {
            log("updateFloatingStyle error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void setupTouchListener() {
        final int[] lastX = new int[1];
        final int[] lastY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];
        final boolean[] moved = {false};
        final long[] tapTime = {0};
        
        mFloatView.setOnTouchListener((v, event) -> {
            try {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchX[0] = event.getRawX();
                        touchY[0] = event.getRawY();
                        lastX[0] = mFloatParams.x;
                        lastY[0] = mFloatParams.y;
                        moved[0] = false;
                        tapTime[0] = System.currentTimeMillis();
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchX[0];
                        float dy = event.getRawY() - touchY[0];
                        
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            moved[0] = true;
                            mFloatParams.x = lastX[0] + (int) dx;
                            mFloatParams.y = lastY[0] + (int) dy;
                            
                            if (mFloatView.getParent() != null) {
                                mWindowManager.updateViewLayout(mFloatView, mFloatParams);
                            }
                        }
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        long duration = System.currentTimeMillis() - tapTime[0];
                        if (!moved[0] && duration < 300) {
                            // 短按切换时间源
                            changeTimeSource();
                        } else if (!moved[0]) {
                            // 长按打开设置
                            openSettings();
                        }
                        // 保存位置
                        mPrefs.edit()
                            .putInt(KEY_FLOAT_X, mFloatParams.x)
                            .putInt(KEY_FLOAT_Y, mFloatParams.y)
                            .apply();
                        return true;
                }
            } catch (Exception e) {
                log("Touch listener error: " + e.getMessage());
                e.printStackTrace();
            }
            return false;
        });
    }

    private void changeTimeSource() {
        if ("taobao".equals(mTimeSource)) {
            mTimeSource = "meituan";
        } else if ("meituan".equals(mTimeSource)) {
            mTimeSource = "local";
        } else {
            mTimeSource = "taobao";
        }
        
        mPrefs.edit().putString(KEY_TIME_SOURCE, mTimeSource).apply();
        updateSourceDisplay();
        mOffsetMs = 0;
        syncTime();
        updateNotification();
        
        log("Source changed to: " + mTimeSource);
    }

    private void openSettings() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            log("Opening settings");
        } catch (Exception e) {
            log("openSettings error: " + e.getMessage());
        }
    }

    private String getSourceShortName() {
        return "taobao".equals(mTimeSource) ? "淘" : 
               "meituan".equals(mTimeSource) ? "团" : "本";
    }

    private String getSourceDisplayName() {
        return "taobao".equals(mTimeSource) ? "淘宝时间" : 
               "meituan".equals(mTimeSource) ? "美团时间" : "本地时间";
    }

    private void startClock() {
        mTimeRunnable = new Runnable() {
            @Override
            public void run() {
                updateTime();
                if (mHandler != null) {
                    mHandler.postDelayed(this, UPDATE_INTERVAL_MS);  // ✅ 使用常量
                }
            }
        };
        mHandler.post(mTimeRunnable);
        log("Clock started");
    }

    private void updateTime() {
        try {
            long now = System.currentTimeMillis() + mOffsetMs;
            Date date = new Date(now);
            
            // ✅ 修复: 使用线程安全的 SimpleDateFormat
            String timeStr;
            synchronized (TIME_FORMAT) {
                timeStr = TIME_FORMAT.format(date);
            }
            
            mCurrentTimeStr = timeStr;
            
            if (mTimeText != null) {
                mTimeText.setText(mCurrentTimeStr);
            }
            if (mMillisText != null) {
                // 提取毫秒部分
                long millis = now % 1000;
                mCurrentMillisStr = String.format(Locale.getDefault(), "%03d", millis);
                mMillisText.setText("." + mCurrentMillisStr);
            }
            if (mDateText != null) {
                SimpleDateFormat dateFmt = new SimpleDateFormat("MM/dd", Locale.getDefault());
                mDateText.setText(dateFmt.format(date));
            }
            
            // 每100ms更新一次通知
            if (now % 100 < 50) {
                updateNotification();
            }
            
            // 检查是否需要切换日夜间模式（自动模式）
            if (mThemeMode == 0) {
                boolean newNight = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 19 || 
                                   java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 7;
                if (newNight != mIsNightMode) {
                    mIsNightMode = newNight;
                    updateFloatingStyle();
                    log("Auto theme switched: isNight=" + mIsNightMode);
                }
            }
            
        } catch (Exception e) {
            log("updateTime error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void syncTime() {
        // ✅ 修复: 使用 compareAndSet 确保线程安全
        if (!mIsSyncing.compareAndSet(false, true)) {
            return;
        }
        
        if ("local".equals(mTimeSource)) {
            mOffsetMs = 0;
            mPrefs.edit().putLong(KEY_OFFSET_MS, 0).apply();
            updateSyncStatus(2);
            log("Local time mode, offset=0");
            mIsSyncing.set(false);
            return;
        }
        
        updateSyncStatus(0);
        log("Syncing time from: " + mTimeSource);
        
        // 显示 Live Update - 同步中
        if (mLiveUpdateManager != null && mLiveUpdateManager.canPostLiveUpdates()) {
            mLiveUpdateManager.showTimeSyncing(mTimeSource);
        }
        
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String url = "taobao".equals(mTimeSource) 
                    ? "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
                    : "https://api.meituan.com/nationalTimestamp";
                
                long localBefore = System.currentTimeMillis();
                
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);  // ✅ 使用常量
                conn.setReadTimeout(READ_TIMEOUT_MS);        // ✅ 使用常量
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    // ✅ 修复: 使用 try-with-resources 自动关闭资源
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        
                        long localAfter = System.currentTimeMillis();
                        long localMid = (localBefore + localAfter) / 2;
                        
                        long serverTime = parseServerTime(response.toString());
                        if (serverTime > 0) {
                            mOffsetMs = serverTime - localMid;
                            mPrefs.edit().putLong(KEY_OFFSET_MS, mOffsetMs).apply();
                            updateSyncStatus(1);
                            log("Time synced: offset=" + mOffsetMs + "ms");
                            
                            // 显示 Live Update - 同步成功
                            if (mLiveUpdateManager != null && mLiveUpdateManager.canPostLiveUpdates()) {
                                mLiveUpdateManager.showTimeSyncSuccess(mTimeSource, mOffsetMs);
                            }
                        } else {
                            updateSyncStatus(-1);
                            log("Failed to parse server time");
                            
                            // 显示 Live Update - 同步失败
                            if (mLiveUpdateManager != null && mLiveUpdateManager.canPostLiveUpdates()) {
                                mLiveUpdateManager.showTimeSyncFailed(mTimeSource);
                            }
                        }
                    }
                } else {
                    updateSyncStatus(-1);
                    log("HTTP error: " + responseCode);
                    
                    // 显示 Live Update - 同步失败
                    if (mLiveUpdateManager != null && mLiveUpdateManager.canPostLiveUpdates()) {
                        mLiveUpdateManager.showTimeSyncFailed(mTimeSource);
                    }
                }
                
            } catch (Exception e) {
                log("syncTime error: " + e.getMessage());
                e.printStackTrace();
                updateSyncStatus(-1);
                
                // 显示 Live Update - 同步失败
                if (mLiveUpdateManager != null && mLiveUpdateManager.canPostLiveUpdates()) {
                    mLiveUpdateManager.showTimeSyncFailed(mTimeSource);
                }
            } finally {
                if (conn != null) conn.disconnect();
                mIsSyncing.set(false);  // ✅ 确保重置
            }
        }).start();
    }

    private void updateSyncStatus(int status) {
        mHandler.post(() -> {
            if (mSyncDot != null) {
                int color;
                switch (status) {
                    case 0: color = 0xFF2196F3; break;  // 同步中-蓝色
                    case 1: color = 0xFF4CAF50; break; // 成功-绿色
                    case 2: color = 0xFF9E9E9E; break; // 本地模式-灰色
                    default: color = 0xFFF44336;      // 失败-红色
                }
                mSyncDot.setBackgroundColor(color);
            }
        });
    }

    private long parseServerTime(String response) {
        try {
            JSONObject json = new JSONObject(response);
            
            long timestamp = 0;
            
            // 尝试 data.t 字段
            if (json.has("data")) {
                JSONObject data = json.getJSONObject("data");
                if (data.has("t")) {
                    String t = data.getString("t");
                    timestamp = Long.parseLong(t);
                }
            }
            
            // 尝试 t 字段
            if (timestamp == 0 && json.has("t")) {
                timestamp = json.getLong("t");
            }
            
            // 尝试 timestamp 字段
            if (timestamp == 0 && json.has("timestamp")) {
                timestamp = json.getLong("timestamp");
            }
            
            // ✅ 统一处理时间单位
            if (timestamp > 0) {
                // 如果是秒级时间戳（< 10000000000），转换为毫秒
                if (timestamp < 10000000000L) {
                    return timestamp * 1000;
                } else {
                    return timestamp;
                }
            }
        } catch (Exception e) {
            log("Failed to parse server time: " + e.getMessage());
            e.printStackTrace();
        }
        return 0;
    }

    private void log(String message) {
        Log.d(TAG, message);
        saveLogToFile(message);
    }

    private void saveLogToFile(String message) {
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            String logLine = timestamp + " | " + message + "\n";
            
            String filename = getFilesDir() + "/floattime.log";
            java.io.File file = new java.io.File(filename);
            
            // ✅ 修复: 使用 RandomAccessFile，高效且线程安全
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
                long fileSize = raf.length();
                
                // 如果文件过大，删除旧内容
                if (fileSize > LOG_FILE_MAX_SIZE) {
                    raf.setLength(0);
                }
                
                raf.seek(raf.length());
                raf.writeBytes(logLine);
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    public static String getLogFilePath(Context context) {
        return context.getFilesDir() + "/floattime.log";
    }

    @Override
    public void onDestroy() {
        log("onDestroy called");
        sIsRunning.set(false);
        
        // 清理 Live Updates
        if (mLiveUpdateManager != null) {
            mLiveUpdateManager.clearAll();
        }
        
        if (mHandler != null && mTimeRunnable != null) {
            mHandler.removeCallbacks(mTimeRunnable);
        }
        
        if (mFloatView != null && mFloatView.getParent() != null) {
            try {
                mWindowManager.removeView(mFloatView);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
