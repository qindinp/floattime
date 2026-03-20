package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 悬浮时间前台服务
 * 
 * 根据 Google 开发者文档实现：
 * 1. 前台服务必须在 5 秒内调用 startForeground()
 * 2. 通知必须设置 channelId (Android 8.0+)
 * 3. 悬浮窗使用 TYPE_APPLICATION_OVERLAY (Android 8.0+)
 */
public class FloatTimeService extends Service {

    private static final String CHANNEL_ID = "float_time_channel";
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final int NOTIFICATION_ID = 20240320;

    // 服务运行状态
    private static final AtomicBoolean sIsRunning = new AtomicBoolean(false);
    public static boolean isRunning() {
        return sIsRunning.get();
    }

    // 系统服务
    private WindowManager mWindowManager;
    private NotificationManager mNotificationManager;
    
    // 悬浮视图
    private View mFloatView;
    private WindowManager.LayoutParams mFloatParams;
    
    // UI 控件
    private TextView mTimeText;
    private TextView mDateText;
    private TextView mSourceText;
    private View mSyncDot;
    
    // 时间相关
    private Handler mHandler;
    private Runnable mTimeRunnable;
    private String mTimeSource = "taobao"; // taobao, meituan, local
    private long mOffsetMs = 0;
    private boolean mIsSyncing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 标记服务运行
        sIsRunning.set(true);
        
        // 初始化 Handler
        mHandler = new Handler(Looper.getMainLooper());
        
        try {
            // 1. 先创建通知渠道
            createNotificationChannel();
            
            // 2. 立即启动前台服务（必须在 5 秒内）
            startForeground(NOTIFICATION_ID, createNotification());
            
            // 3. 获取 WindowManager
            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (mWindowManager == null) {
                stopSelf();
                return;
            }
            
            mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            
            // 4. 创建悬浮窗
            createFloatingView();
            
            // 5. 启动时钟
            startClock();
            
            // 6. 同步时间
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

    /**
     * 创建通知渠道 (Android 8.0+ 必须)
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("悬浮时间服务运行中");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 创建通知
     */
    private Notification createNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("悬浮时间")
            .setContentText("服务运行中")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false);
        
        return builder.build();
    }

    /**
     * 创建悬浮窗
     */
    private void createFloatingView() {
        // 加载布局
        mFloatView = LayoutInflater.from(this).inflate(R.layout.float_ball, null);
        
        // 获取控件
        mTimeText = mFloatView.findViewById(R.id.timeText);
        mDateText = mFloatView.findViewById(R.id.dateText);
        mSourceText = mFloatView.findViewById(R.id.sourceText);
        mSyncDot = mFloatView.findViewById(R.id.syncDot);
        
        // 设置布局参数
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }
        
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
        mFloatParams.y = 100;
        
        // 设置触摸监听
        setupTouchListener();
        
        // 添加到 WindowManager
        mWindowManager.addView(mFloatView, mFloatParams);
    }

    /**
     * 设置触摸监听 - 拖拽移动
     */
    private void setupTouchListener() {
        final int[] lastX = new int[1];
        final int[] lastY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];
        final boolean[] moved = {false};
        
        mFloatView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
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
                                
                                if (mFloatView != null && mFloatView.getParent() != null) {
                                    mWindowManager.updateViewLayout(mFloatView, mFloatParams);
                                }
                            }
                            return true;
                            
                        case MotionEvent.ACTION_UP:
                            if (!moved[0]) {
                                // 点击 - 可以扩展为打开设置
                                changeTimeSource();
                            }
                            return true;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return false;
            }
        });
    }

    /**
     * 切换时间源
     */
    private void changeTimeSource() {
        if ("taobao".equals(mTimeSource)) {
            mTimeSource = "meituan";
            if (mSourceText != null) mSourceText.setText("团");
        } else if ("meituan".equals(mTimeSource)) {
            mTimeSource = "local";
            if (mSourceText != null) mSourceText.setText("本");
        } else {
            mTimeSource = "taobao";
            if (mSourceText != null) mSourceText.setText("淘");
        }
        
        updateTheme();
        syncTime();
    }

    /**
     * 更新主题颜色
     */
    private void updateTheme() {
        try {
            int bgColor;
            if ("taobao".equals(mTimeSource)) {
                bgColor = 0xEBFF5000; // 淘宝橙
            } else if ("meituan".equals(mTimeSource)) {
                bgColor = 0xEBFFD100; // 美团黄
            } else {
                bgColor = 0xEB4A90E2; // 蓝色
            }
            
            if (mFloatView != null) {
                mFloatView.setBackgroundColor(bgColor);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 启动时钟
     */
    private void startClock() {
        mTimeRunnable = new Runnable() {
            @Override
            public void run() {
                updateTime();
                if (mHandler != null) {
                    mHandler.postDelayed(this, 1000);
                }
            }
        };
        
        if (mHandler != null) {
            mHandler.post(mTimeRunnable);
        }
    }

    /**
     * 更新时间显示
     */
    private void updateTime() {
        try {
            long now = System.currentTimeMillis() + mOffsetMs;
            Date date = new Date(now);
            
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault());
            
            if (mTimeText != null) {
                mTimeText.setText(timeFmt.format(date));
            }
            if (mDateText != null) {
                mDateText.setText(dateFmt.format(date));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 同步网络时间
     */
    private void syncTime() {
        if (mIsSyncing) return;
        if ("local".equals(mTimeSource)) {
            mOffsetMs = 0;
            return;
        }
        
        mIsSyncing = true;
        
        // 更新 UI 显示同步中
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mSyncDot != null) {
                    mSyncDot.setBackgroundColor(0xFF1890FF);
                }
            }
        });
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = "taobao".equals(mTimeSource) 
                        ? "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
                        : "https://api.meituan.com/nationalTimestamp";
                    
                    long localBefore = System.currentTimeMillis();
                    
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "*/*");
                    
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
                        
                        // 解析 JSON
                        long serverTime = parseServerTime(response.toString());
                        if (serverTime > 0) {
                            mOffsetMs = serverTime - localMid;
                        }
                        
                        // 更新 UI 显示成功
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mSyncDot != null) {
                                    mSyncDot.setBackgroundColor(0xFF52c41A);
                                }
                            }
                        });
                    }
                    
                    conn.disconnect();
                    
                } catch (Exception e) {
                    e.printStackTrace();
                    // 更新 UI 显示失败
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mSyncDot != null) {
                                mSyncDot.setBackgroundColor(0xFFff4d4f);
                            }
                        }
                    });
                } finally {
                    mIsSyncing = false;
                }
            }
        }).start();
    }

    /**
     * 解析服务器时间
     */
    private long parseServerTime(String response) {
        try {
            JSONObject json = new JSONObject(response);
            
            // 淘宝格式: {"data":{"t":"1234567890123"}}
            if (json.has("data")) {
                JSONObject data = json.getJSONObject("data");
                if (data.has("t")) {
                    return data.getLong("t");
                }
            }
            
            // 美团格式
            if (json.has("t")) {
                return json.getLong("t") * 1000;
            }
            if (json.has("timestamp")) {
                long ts = json.getLong("timestamp");
                return String.valueOf(ts).length() == 10 ? ts * 1000 : ts;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public void onDestroy() {
        sIsRunning.set(false);
        
        // 停止时钟
        if (mHandler != null && mTimeRunnable != null) {
            mHandler.removeCallbacks(mTimeRunnable);
        }
        
        // 移除悬浮窗
        if (mFloatView != null && mFloatView.getParent() != null) {
            try {
                mWindowManager.removeView(mFloatView);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        mFloatView = null;
        mWindowManager = null;
        mHandler = null;
        
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
