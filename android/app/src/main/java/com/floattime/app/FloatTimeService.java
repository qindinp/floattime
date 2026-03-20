package com.floattime.app;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;

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

public class FloatTimeService extends Service {

    private static final String CHANNEL_ID = "float_time_channel";
    private static final int NOTIFICATION_ID = 20240320;
    
    // 静态标志位，用于判断服务是否运行
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    public static boolean isRunning() {
        return isRunning.get();
    }
    
    private WindowManager windowManager;
    private View floatView;
    private View settingsView;
    private WindowManager.LayoutParams floatParams;
    private WindowManager.LayoutParams settingsParams;
    
    private Handler handler;
    private Runnable timeUpdateRunnable;
    
    private String timeSource = "taobao"; // taobao, meituan, local
    private long offsetMs = 0;
    private String lastSyncTime = "--:--:--";
    private boolean isSettingsOpen = false;
    
    // 悬浮球控件
    private TextView timeText;
    private TextView dateText;
    private TextView sourceText;
    private View syncDot;
    
    // 设置面板控件
    private TextView settingsCurrentSource;
    private TextView settingsOffset;
    private TextView settingsLastSync;
    private TextView stopwatchDisplay;
    
    // 秒表
    private boolean stopwatchRunning = false;
    private long stopwatchStart = 0;
    private long stopwatchElapsed = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        
        isRunning.set(true);
        handler = new Handler(Looper.getMainLooper());
        
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());
            
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                stopSelf();
                return;
            }
            
            createFloatingBall();
            createSettingsPanel();
            startClock();
            syncTime();
            
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 确保服务不会被意外杀死后重启
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "悬浮时间",
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("显示悬浮时间服务");
                channel.setShowBadge(false);
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private Notification createNotification() {
        RemoteViews collapsedView = new RemoteViews(getPackageName(), R.layout.notification_collapsed);
        RemoteViews expandedView = new RemoteViews(getPackageName(), R.layout.notification_expanded);
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_time)
            .setContentTitle("悬浮时间")
            .setContentText("服务运行中")
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false);
        
        return builder.build();
    }

    private void createFloatingBall() {
        try {
            floatView = LayoutInflater.from(this).inflate(R.layout.float_ball, null);
            
            timeText = floatView.findViewById(R.id.timeText);
            dateText = floatView.findViewById(R.id.dateText);
            sourceText = floatView.findViewById(R.id.sourceText);
            syncDot = floatView.findViewById(R.id.syncDot);
            
            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_PHONE;
            }
            
            floatParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE 
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            );
            floatParams.gravity = Gravity.TOP | Gravity.START;
            floatParams.x = 100;
            floatParams.y = 200;
            
            updateTheme();
            setupFloatBallTouch();
            
            windowManager.addView(floatView, floatParams);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupFloatBallTouch() {
        if (floatView == null) return;
        
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];
        final int[] lastX = new int[1];
        final int[] lastY = new int[1];
        final boolean[] moved = {false};
        
        floatView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                try {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            touchX[0] = event.getRawX();
                            touchY[0] = event.getRawY();
                            lastX[0] = floatParams.x;
                            lastY[0] = floatParams.y;
                            moved[0] = false;
                            return true;
                            
                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - touchX[0];
                            float dy = event.getRawY() - touchY[0];
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                moved[0] = true;
                                floatParams.x = lastX[0] + (int) dx;
                                floatParams.y = lastY[0] + (int) dy;
                                if (floatView != null && floatView.getParent() != null) {
                                    windowManager.updateViewLayout(floatView, floatParams);
                                }
                            }
                            return true;
                            
                        case MotionEvent.ACTION_UP:
                            if (!moved[0]) {
                                toggleSettings();
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

    private void createSettingsPanel() {
        try {
            settingsView = LayoutInflater.from(this).inflate(R.layout.settings_panel, null);
            
            settingsCurrentSource = settingsView.findViewById(R.id.currentSource);
            settingsOffset = settingsView.findViewById(R.id.offsetValue);
            settingsLastSync = settingsView.findViewById(R.id.lastSyncTime);
            stopwatchDisplay = settingsView.findViewById(R.id.stopwatchDisplay);
            
            // 时间源选择
            LinearLayout sourceTaobao = settingsView.findViewById(R.id.sourceTaobao);
            LinearLayout sourceMeituan = settingsView.findViewById(R.id.sourceMeituan);
            LinearLayout sourceLocal = settingsView.findViewById(R.id.sourceLocal);
            
            if (sourceTaobao != null) sourceTaobao.setOnClickListener(v -> selectSource("taobao"));
            if (sourceMeituan != null) sourceMeituan.setOnClickListener(v -> selectSource("meituan"));
            if (sourceLocal != null) sourceLocal.setOnClickListener(v -> selectSource("local"));
            
            // 同步按钮
            View syncBtn = settingsView.findViewById(R.id.syncBtn);
            if (syncBtn != null) syncBtn.setOnClickListener(v -> syncTime());
            
            // 秒表
            View stopwatchToggle = settingsView.findViewById(R.id.stopwatchToggle);
            View stopwatchReset = settingsView.findViewById(R.id.stopwatchReset);
            if (stopwatchToggle != null) stopwatchToggle.setOnClickListener(v -> toggleStopwatch());
            if (stopwatchReset != null) stopwatchReset.setOnClickListener(v -> resetStopwatch());
            
            // 关闭按钮
            View closeBtn = settingsView.findViewById(R.id.closeBtn);
            if (closeBtn != null) closeBtn.setOnClickListener(v -> hideSettings());
            
            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_PHONE;
            }
            
            settingsParams = new WindowManager.LayoutParams(
                (int) (280 * getResources().getDisplayMetrics().density),
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            );
            settingsParams.gravity = Gravity.CENTER;
            settingsParams.y = -50;
            
            checkSourceSelection();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void toggleSettings() {
        if (isSettingsOpen) {
            hideSettings();
        } else {
            showSettings();
        }
    }

    private void showSettings() {
        try {
            if (!isSettingsOpen && settingsView != null && settingsView.getParent() == null) {
                windowManager.addView(settingsView, settingsParams);
                isSettingsOpen = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void hideSettings() {
        try {
            if (isSettingsOpen && settingsView != null && settingsView.getParent() != null) {
                windowManager.removeView(settingsView);
                isSettingsOpen = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void selectSource(String source) {
        timeSource = source;
        updateTheme();
        checkSourceSelection();
        updateSettingsUI();
        hideSettings();
        syncTime();
    }

    private void updateTheme() {
        try {
            if (floatView == null) return;
            
            int bgRes;
            int textColor;
            
            switch (timeSource) {
                case "taobao":
                    bgRes = R.drawable.float_ball_bg_taobao;
                    textColor = getResources().getColor(R.color.theme_taobao);
                    break;
                case "meituan":
                    bgRes = R.drawable.float_ball_bg_meituan;
                    textColor = getResources().getColor(R.color.theme_meituan);
                    break;
                default:
                    bgRes = R.drawable.float_ball_bg_local;
                    textColor = getResources().getColor(R.color.theme_local);
                    break;
            }
            
            floatView.setBackgroundResource(bgRes);
            if (sourceText != null) sourceText.setTextColor(textColor);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkSourceSelection() {
        if (settingsView == null) return;
        
        try {
            TextView checkTaobao = settingsView.findViewById(R.id.checkTaobao);
            TextView checkMeituan = settingsView.findViewById(R.id.checkMeituan);
            TextView checkLocal = settingsView.findViewById(R.id.checkLocal);
            
            LinearLayout sourceTaobao = settingsView.findViewById(R.id.sourceTaobao);
            LinearLayout sourceMeituan = settingsView.findViewById(R.id.sourceMeituan);
            LinearLayout sourceLocal = settingsView.findViewById(R.id.sourceLocal);
            
            if (checkTaobao != null) checkTaobao.setVisibility(timeSource.equals("taobao") ? View.VISIBLE : View.GONE);
            if (checkMeituan != null) checkMeituan.setVisibility(timeSource.equals("meituan") ? View.VISIBLE : View.GONE);
            if (checkLocal != null) checkLocal.setVisibility(timeSource.equals("local") ? View.VISIBLE : View.GONE);
            
            if (sourceTaobao != null) sourceTaobao.setSelected(timeSource.equals("taobao"));
            if (sourceMeituan != null) sourceMeituan.setSelected(timeSource.equals("meituan"));
            if (sourceLocal != null) sourceLocal.setSelected(timeSource.equals("local"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startClock() {
        timeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateTime();
                if (handler != null) {
                    handler.postDelayed(this, 1000);
                }
            }
        };
        if (handler != null) {
            handler.post(timeUpdateRunnable);
        }
    }

    private void updateTime() {
        try {
            long now = System.currentTimeMillis() + offsetMs;
            Date date = new Date(now);
            
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault());
            
            if (timeText != null) timeText.setText(timeFmt.format(date));
            if (dateText != null) dateText.setText(dateFmt.format(date));
            if (sourceText != null) sourceText.setText(timeSource.equals("taobao") ? "淘" : (timeSource.equals("meituan") ? "团" : "本"));
            
            updateNotification(timeFmt.format(date), dateFmt.format(date));
            
            if (stopwatchRunning) {
                stopwatchElapsed = System.currentTimeMillis() - stopwatchStart;
                updateStopwatchDisplay();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateNotification(String time, String dateStr) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            
            RemoteViews collapsed = new RemoteViews(getPackageName(), R.layout.notification_collapsed);
            collapsed.setTextViewText(R.id.notifTime, time);
            collapsed.setTextViewText(R.id.notifDate, dateStr);
            
            RemoteViews expanded = new RemoteViews(getPackageName(), R.layout.notification_expanded);
            expanded.setTextViewText(R.id.notifTimeBig, time);
            expanded.setTextViewText(R.id.notifDateBig, dateStr);
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_time)
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false);
            
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void syncTime() {
        if (syncDot != null) syncDot.setBackgroundResource(R.drawable.sync_dot_syncing);
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final long offset = fetchTimeOffset(timeSource);
                    offsetMs = offset;
                    lastSyncTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    
                    if (handler != null) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (syncDot != null) syncDot.setBackgroundResource(R.drawable.sync_dot_success);
                                updateSettingsUI();
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    if (handler != null) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (syncDot != null) syncDot.setBackgroundResource(R.drawable.sync_dot_error);
                            }
                        });
                    }
                }
            }
        }).start();
    }

    private long fetchTimeOffset(String source) throws Exception {
        if (source.equals("local")) return 0;
        
        String url = source.equals("taobao") 
            ? "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp" 
            : "https://api.meituan.com/nationalTimestamp";
        
        long localBefore = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("GET");
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        long localAfter = System.currentTimeMillis();
        long localMid = (localBefore + localAfter) / 2;
        
        try {
            JSONObject json = new JSONObject(response.toString());
            long serverTime = 0;
            
            if (json.has("data")) {
                JSONObject data = json.getJSONObject("data");
                if (data.has("t")) {
                    serverTime = data.getLong("t");
                }
            } else if (json.has("t")) {
                serverTime = json.getLong("t") * 1000;
            } else if (json.has("timestamp")) {
                serverTime = json.getLong("timestamp") * 1000;
            }
            
            if (serverTime > 0) {
                return serverTime - localMid;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return 0;
    }

    private void updateSettingsUI() {
        if (settingsView == null) return;
        
        try {
            String sourceName;
            int sourceColor;
            
            switch (timeSource) {
                case "taobao":
                    sourceName = "淘宝时间";
                    sourceColor = getResources().getColor(R.color.theme_taobao);
                    break;
                case "meituan":
                    sourceName = "美团时间";
                    sourceColor = getResources().getColor(R.color.theme_meituan);
                    break;
                default:
                    sourceName = "本地时间";
                    sourceColor = getResources().getColor(R.color.theme_local);
                    break;
            }
            
            if (settingsCurrentSource != null) {
                settingsCurrentSource.setText(sourceName);
                settingsCurrentSource.setTextColor(sourceColor);
            }
            
            if (settingsOffset != null) {
                String offsetText = offsetMs == 0 ? "±0ms" : 
                    (offsetMs > 0 ? "+" + offsetMs + "ms" : offsetMs + "ms");
                settingsOffset.setText(offsetText);
            }
            
            if (settingsLastSync != null) {
                settingsLastSync.setText(lastSyncTime);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void toggleStopwatch() {
        if (settingsView == null) return;
        
        Button toggleBtn = settingsView.findViewById(R.id.stopwatchToggle);
        
        if (stopwatchRunning) {
            stopwatchRunning = false;
            if (toggleBtn != null) toggleBtn.setText("▶");
        } else {
            stopwatchRunning = true;
            stopwatchStart = System.currentTimeMillis() - stopwatchElapsed;
            if (toggleBtn != null) toggleBtn.setText("⏸");
        }
    }

    private void resetStopwatch() {
        stopwatchRunning = false;
        stopwatchElapsed = 0;
        updateStopwatchDisplay();
        
        if (settingsView != null) {
            Button toggleBtn = settingsView.findViewById(R.id.stopwatchToggle);
            if (toggleBtn != null) toggleBtn.setText("▶");
        }
    }

    private void updateStopwatchDisplay() {
        if (stopwatchDisplay == null) return;
        
        long ms = stopwatchElapsed;
        long seconds = (ms / 1000) % 60;
        long minutes = (ms / (1000 * 60)) % 60;
        long millis = (ms % 1000) / 10;
        
        String display = String.format(Locale.getDefault(), "%02d:%02d.%02d", minutes, seconds, millis);
        stopwatchDisplay.setText(display);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 根据小米文档，正确处理 Configuration 变化
        // 这里可以更新悬浮球位置等
    }

    @Override
    public void onDestroy() {
        isRunning.set(false);
        
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        
        try {
            if (floatView != null && floatView.getParent() != null) {
                windowManager.removeView(floatView);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        try {
            if (settingsView != null && settingsView.getParent() != null) {
                windowManager.removeView(settingsView);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        floatView = null;
        settingsView = null;
        windowManager = null;
        
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
