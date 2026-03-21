package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 悬浮时间前台服务 - 带毫秒显示
 */
public class FloatTimeService extends Service {

    private static final String CHANNEL_ID = "float_time_channel";
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final int NOTIFICATION_ID = 20240320;

    private static final AtomicBoolean sIsRunning = new AtomicBoolean(false);
    public static boolean isRunning() {
        return sIsRunning.get();
    }

    private WindowManager mWindowManager;
    private NotificationManager mNotificationManager;
    private View mFloatView;
    private WindowManager.LayoutParams mFloatParams;
    
    private TextView mTimeText;
    private TextView mMillisText;
    private TextView mDateText;
    private TextView mSourceText;
    private View mSyncDot;
    
    private Handler mHandler;
    private Runnable mTimeRunnable;
    private String mTimeSource = "taobao";
    private long mOffsetMs = 0;
    private boolean mIsSyncing = false;
    private String mCurrentTimeStr = "";
    private String mCurrentMillisStr = "";

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning.set(true);
        mHandler = new Handler(Looper.getMainLooper());
        
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());
            
            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            
            if (mWindowManager != null) {
                createFloatingView();
            }
            
            startClock();
            syncTime();
            
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
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
        RemoteViews collapsedView = new RemoteViews(getPackageName(), R.layout.notification_collapsed);
        RemoteViews expandedView = new RemoteViews(getPackageName(), R.layout.notification_expanded);
        
        String timeStr = mCurrentTimeStr.isEmpty() ? "--:--:--" : mCurrentTimeStr;
        collapsedView.setTextViewText(R.id.notification_time, timeStr);
        expandedView.setTextViewText(R.id.notification_time, timeStr + (mCurrentMillisStr.isEmpty() ? "" : "." + mCurrentMillisStr.substring(0, Math.min(3, mCurrentMillisStr.length()))));
        
        String sourceText = getSourceDisplayName();
        collapsedView.setTextViewText(R.id.notification_source, sourceText);
        expandedView.setTextViewText(R.id.notification_source, sourceText);
        
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("悬浮时间")
            .setContentText(timeStr)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView);
        
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(new NotificationCompat.DecoratedCustomViewStyle());
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        
        return builder.build();
    }

    private void updateNotification() {
        if (mNotificationManager != null && !mCurrentTimeStr.isEmpty()) {
            try {
                mNotificationManager.notify(NOTIFICATION_ID, createNotification());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void createFloatingView() {
        mFloatView = LayoutInflater.from(this).inflate(R.layout.float_ball, null);
        
        mTimeText = mFloatView.findViewById(R.id.timeText);
        mMillisText = mFloatView.findViewById(R.id.millisText);
        mDateText = mFloatView.findViewById(R.id.dateText);
        mSourceText = mFloatView.findViewById(R.id.sourceText);
        mSyncDot = mFloatView.findViewById(R.id.syncDot);
        
        updateTheme();
        
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O 
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            : WindowManager.LayoutParams.TYPE_PHONE;
        
        mFloatParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE 
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        
        mFloatParams.gravity = Gravity.TOP | Gravity.START;
        mFloatParams.x = 50;
        mFloatParams.y = 200;
        
        setupTouchListener();
        mWindowManager.addView(mFloatView, mFloatParams);
    }

    private void setupTouchListener() {
        final int[] lastX = new int[1];
        final int[] lastY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];
        final boolean[] moved = {false};
        
        mFloatView.setOnTouchListener((v, event) -> {
            try {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchX[0] = event.getRawX();
                        touchY[0] = event.getRawY();
                        lastX[0] = mFloatParams.x;
                        lastY[0] = mFloatParams.y;
                        moved[0] = false;
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
                        if (!moved[0]) {
                            changeTimeSource();
                        }
                        return true;
                }
            } catch (Exception e) {
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
        
        String sourceLabel = getSourceShortName();
        if (mSourceText != null) {
            mSourceText.setText(sourceLabel);
        }
        
        updateTheme();
        mOffsetMs = 0;
        syncTime();
        updateNotification();
    }

    private String getSourceShortName() {
        return "taobao".equals(mTimeSource) ? "淘" : 
               "meituan".equals(mTimeSource) ? "团" : "本";
    }

    private String getSourceDisplayName() {
        return "taobao".equals(mTimeSource) ? "淘宝时间" : 
               "meituan".equals(mTimeSource) ? "美团时间" : "本地时间";
    }

    private void updateTheme() {
        try {
            int bgColor;
            if ("taobao".equals(mTimeSource)) {
                bgColor = 0xFFFF5A3F;
            } else if ("meituan".equals(mTimeSource)) {
                bgColor = 0xFFFFB800;
            } else {
                bgColor = 0xFF2196F3;
            }
            
            if (mFloatView != null) {
                mFloatView.setBackgroundColor(bgColor);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startClock() {
        mTimeRunnable = new Runnable() {
            @Override
            public void run() {
                updateTime();
                if (mHandler != null) {
                    mHandler.postDelayed(this, 50); // 50ms刷新，确保毫秒流畅
                }
            }
        };
        mHandler.post(mTimeRunnable);
    }

    private void updateTime() {
        try {
            long now = System.currentTimeMillis() + mOffsetMs;
            Date date = new Date(now);
            
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            SimpleDateFormat millisFmt = new SimpleDateFormat("SSS", Locale.getDefault());
            SimpleDateFormat dateFmt = new SimpleDateFormat("MM/dd", Locale.getDefault());
            
            mCurrentTimeStr = timeFmt.format(date);
            mCurrentMillisStr = millisFmt.format(date);
            
            if (mTimeText != null) {
                mTimeText.setText(mCurrentTimeStr);
            }
            if (mMillisText != null) {
                mMillisText.setText("." + mCurrentMillisStr);
            }
            if (mDateText != null) {
                mDateText.setText(dateFmt.format(date));
            }
            
            // 每100ms更新一次通知
            if (now % 100 < 50) {
                updateNotification();
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void syncTime() {
        if (mIsSyncing) return;
        if ("local".equals(mTimeSource)) {
            mOffsetMs = 0;
            updateSyncStatus(2);
            return;
        }
        
        mIsSyncing = true;
        updateSyncStatus(0);
        
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String url = "taobao".equals(mTimeSource) 
                    ? "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
                    : "https://api.meituan.com/nationalTimestamp";
                
                long localBefore = System.currentTimeMillis();
                
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
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
                        updateSyncStatus(1);
                    } else {
                        updateSyncStatus(-1);
                    }
                } else {
                    updateSyncStatus(-1);
                }
                
            } catch (Exception e) {
                e.printStackTrace();
                updateSyncStatus(-1);
            } finally {
                if (conn != null) conn.disconnect();
                mIsSyncing = false;
            }
        }).start();
    }

    private void updateSyncStatus(int status) {
        mHandler.post(() -> {
            if (mSyncDot != null) {
                int color;
                switch (status) {
                    case 0: color = 0xFF2196F3; break;
                    case 1: color = 0xFF4CAF50; break;
                    case 2: color = 0xFF9E9E9E; break;
                    default: color = 0xFFF44336;
                }
                mSyncDot.setBackgroundColor(color);
            }
        });
    }

    private long parseServerTime(String response) {
        try {
            JSONObject json = new JSONObject(response);
            
            if (json.has("data")) {
                JSONObject data = json.getJSONObject("data");
                if (data.has("t")) {
                    String t = data.getString("t");
                    return Long.parseLong(t);
                }
            }
            
            if (json.has("t")) {
                long t = json.getLong("t");
                return t < 10000000000L ? t * 1000 : t;
            }
            
            if (json.has("timestamp")) {
                long ts = json.getLong("timestamp");
                return ts < 10000000000L ? ts * 1000 : ts;
            }
            if (json.has("time")) {
                long t = json.getLong("time");
                return t < 10000000000L ? t * 1000 : t;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public void onDestroy() {
        sIsRunning.set(false);
        
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
