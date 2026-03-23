package com.floattime.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 主界面 - 负责权限申请、服务启动、主题设置
 * 
 * 改进:
 * - 启用 AppCompatDelegate 暗夜模式支持
 * - 首次启动主动请求权限
 * - 集成 Live Updates 功能
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "FloatTimePrefs";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_PERMISSIONS_REQUESTED = "permissions_requested";

    private Button startBtn;
    private Button stopBtn;
    private TextView statusText;
    private TextView timezoneText;
    private TextView currentTimeText;
    private TextView versionText;
    private RadioGroup themeGroup;
    private RadioButton themeAuto;
    private RadioButton themeLight;
    private RadioButton themeDark;
    private Switch floatWindowSwitch;  // ✅ 悬浮窗开关
    
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private Handler mHandler;
    private Runnable mTimeRunnable;
    private SharedPreferences mPrefs;
    
    // Live Update 管理器
    private LiveUpdateManager mLiveUpdateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // ✅ 第一步: 启用暗夜模式支持 - 跟随系统设置
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        
        // ✅ 第二步: 初始化 SharedPreferences
        mPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        // ✅ 第三步: 初始化 Live Update 管理器
        mLiveUpdateManager = new LiveUpdateManager(this);
        
        // ✅ 第四步: 注册悬浮窗权限回调
        overlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        checkNotificationPermissionAndStart();
                    } else {
                        showStatus("❌ 需要悬浮窗权限");
                    }
                }
            }
        );
        
        // ✅ 第五步: 初始化 UI (必须在权限请求之前)
        setContentView(R.layout.activity_main);
        
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        statusText = findViewById(R.id.statusText);
        timezoneText = findViewById(R.id.timezoneText);
        currentTimeText = findViewById(R.id.currentTimeText);
        versionText = findViewById(R.id.versionText);
        themeGroup = findViewById(R.id.themeGroup);
        themeAuto = findViewById(R.id.themeAuto);
        themeLight = findViewById(R.id.themeLight);
        themeDark = findViewById(R.id.themeDark);
        floatWindowSwitch = findViewById(R.id.floatWindowSwitch);  // ✅ 初始化悬浮窗开关

        startBtn.setOnClickListener(v -> {
            // ✅ 检查悬浮窗权限并显示选项
            checkOverlayPermission();
        });
        stopBtn.setOnClickListener(v -> stopFloatingService());
        
        // ✅ 悬浮窗开关监听
        if (floatWindowSwitch != null) {
            floatWindowSwitch.setChecked(mPrefs.getBoolean("float_window_enabled", true));
            floatWindowSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                mPrefs.edit().putBoolean("float_window_enabled", isChecked).apply();
                if (FloatTimeService.isRunning()) {
                    // 如果服务正在运行，需要重启以应用设置
                    stopFloatingService();
                    mHandler.postDelayed(() -> checkOverlayPermission(), 500);
                }
            });
        }
        
        // 设置版本信息
        if (versionText != null) {
            versionText.setText("FloatTime v1.2.1");
        }
        
        // 设置主题选择并应用保存的主题
        setupThemeSelector();
        applyThemeMode(mPrefs.getInt(KEY_THEME_MODE, 0));
        
        // 显示时区信息
        displayTimezone();
        
        // 启动主界面时钟
        startMainClock();
        
        updateStatus();
        
        // ✅ 第六步: 最后请求权限 (UI 已准备好)
        if (!mPrefs.getBoolean(KEY_PERMISSIONS_REQUESTED, false)) {
            requestInitialPermissions();
            mPrefs.edit().putBoolean(KEY_PERMISSIONS_REQUESTED, true).apply();
        }
        
        Log.d(TAG, "MainActivity onCreate - Dark mode enabled, permissions checked");
    }

    // ✅ 新增: 首次启动权限请求
    private void requestInitialPermissions() {
        Log.d(TAG, "Requesting initial permissions");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 请求通知权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting POST_NOTIFICATIONS permission");
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    1001);
            }
        }
        
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Requesting overlay permission");
                showOverlayPermissionDialog();
            }
        }
    }

    private void setupThemeSelector() {
        int savedTheme = mPrefs.getInt(KEY_THEME_MODE, 0);
        
        if (savedTheme == 0 && themeAuto != null) {
            themeAuto.setChecked(true);
        } else if (savedTheme == 1 && themeLight != null) {
            themeLight.setChecked(true);
        } else if (savedTheme == 2 && themeDark != null) {
            themeDark.setChecked(true);
        }
        
        if (themeGroup != null) {
            themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
                int mode = 0;
                if (checkedId == R.id.themeLight) {
                    mode = 1;
                } else if (checkedId == R.id.themeDark) {
                    mode = 2;
                }
                
                mPrefs.edit().putInt(KEY_THEME_MODE, mode).apply();
                applyThemeMode(mode);
                
                // 如果服务正在运行，更新悬浮窗主题
                if (FloatTimeService.isRunning()) {
                    updateServiceTheme(mode);
                }
                
                Log.d(TAG, "Theme changed to: " + mode);
            });
        }
    }

    private void applyThemeMode(int mode) {
        boolean isNight = false;
        if (mode == 0) {
            // 自动模式
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            isNight = (hour >= 19 || hour < 7);
        } else {
            isNight = (mode == 2);
        }
        
        // ✅ 参考 Android 官方设计标准 - 主界面使用纯黑色
        int bgColor = isNight ? 0xFF121212 : 0xFFFFFFFF;      // 纯黑背景
        int textColor = isNight ? 0xFFFFFFFF : 0xFF1A1A2E;    // 白色文本
        int accentColor = isNight ? 0xFF03DAC6 : 0xFFBB86FC;  // 官方青色/紫色
        
        View root = findViewById(android.R.id.content);
        if (root != null) {
            root.setBackgroundColor(bgColor);
        }
        
        if (statusText != null) statusText.setTextColor(textColor);
        if (timezoneText != null) timezoneText.setTextColor(textColor);
        if (currentTimeText != null) currentTimeText.setTextColor(textColor);
        if (versionText != null) versionText.setTextColor(textColor);
        
        // ✅ 修复: 添加 null 检查
        if (startBtn != null) updateButtonStyle(startBtn, isNight, accentColor);
        if (stopBtn != null) updateButtonStyle(stopBtn, isNight, accentColor);
        
        // ✅ 更新 Switch 颜色
        if (floatWindowSwitch != null) {
            floatWindowSwitch.setTextColor(textColor);
        }
    }

    private void updateButtonStyle(Button btn, boolean isNight, int accentColor) {
        if (btn == null) return;
        
        // ✅ 参考官方设计
        int bgColor = isNight ? 0xFF2A2A2A : 0xFFE0E0E0;
        int textColor = isNight ? accentColor : 0xFF1A1A2E;
        
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dpToPx(12));
        drawable.setColor(bgColor);
        drawable.setStroke(dpToPx(2), textColor);
        
        btn.setBackground(drawable);
        btn.setTextColor(textColor);
    }

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void updateServiceTheme(int mode) {
        Intent intent = new Intent(this, FloatTimeService.class);
        intent.setAction("CHANGE_THEME");
        intent.putExtra("mode", mode);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mHandler != null && mTimeRunnable != null) {
            mHandler.removeCallbacks(mTimeRunnable);
        }
        // 清理 Live Updates
        if (mLiveUpdateManager != null) {
            mLiveUpdateManager.clearAll();
        }
    }
    
    // ==================== Live Update 公共方法 ====================
    
    /**
     * 检查是否支持 Live Updates
     */
    public boolean isSupported() {
        return mLiveUpdateManager != null && mLiveUpdateManager.isSupported();
    }
    
    /**
     * 显示时间同步中
     */
    public void showTimeSyncing(String source) {
        if (mLiveUpdateManager != null) {
            mLiveUpdateManager.showTimeSyncing(source);
        }
    }
    
    /**
     * 显示时间同步成功
     */
    public void showTimeSyncSuccess(String source, long offsetMs) {
        if (mLiveUpdateManager != null) {
            mLiveUpdateManager.showTimeSyncSuccess(source, offsetMs);
        }
    }
    
    /**
     * 显示时间同步失败
     */
    public void showTimeSyncFailed(String source) {
        if (mLiveUpdateManager != null) {
            mLiveUpdateManager.showTimeSyncFailed(source);
        }
    }
    
    /**
     * 启动秒表 Live Update
     */
    public void startStopwatchLiveUpdate() {
        if (mLiveUpdateManager != null) {
            mLiveUpdateManager.startStopwatch();
        }
    }
    
    /**
     * 暂停秒表 Live Update
     */
    public void pauseStopwatchLiveUpdate() {
        if (mLiveUpdateManager != null) {
            mLiveUpdateManager.pauseStopwatch();
        }
    }
    
    /**
     * 停止秒表 Live Update
     */
    public void stopStopwatchLiveUpdate() {
        if (mLiveUpdateManager != null) {
            mLiveUpdateManager.stopStopwatch();
        }
    }

    private void displayTimezone() {
        TimeZone tz = TimeZone.getDefault();
        String tzId = tz.getID();
        String tzName = tz.getDisplayName(false, TimeZone.SHORT);
        
        int offset = tz.getRawOffset() / (1000 * 60 * 60);
        String offsetStr = offset >= 0 ? "GMT+" + offset : "GMT" + offset;
        
        String displayText = tzId + " (" + offsetStr + ")";
        if (timezoneText != null) {
            timezoneText.setText(displayText);
        }
    }

    private void startMainClock() {
        mHandler = new Handler(Looper.getMainLooper());
        mTimeRunnable = new Runnable() {
            @Override
            public void run() {
                updateMainTime();
                if (mHandler != null) {
                    mHandler.postDelayed(this, 50);
                }
            }
        };
        mHandler.post(mTimeRunnable);
    }

    private void updateMainTime() {
        try {
            long now = System.currentTimeMillis();
            Date date = new Date(now);
            
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
            String timeStr = timeFmt.format(date);
            
            if (currentTimeText != null) {
                currentTimeText.setText(timeStr);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateStatus() {
        boolean running = FloatTimeService.isRunning();
        runOnUiThread(() -> {
            if (running) {
                statusText.setText("✅ 悬浮时间服务运行中");
                startBtn.setEnabled(false);
                stopBtn.setEnabled(true);
            } else {
                statusText.setText("⭕ 服务未启动");
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
            }
        });
    }

    private void showStatus(String text) {
        if (statusText != null) {
            runOnUiThread(() -> statusText.setText(text));
        }
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                checkNotificationPermissionAndStart();
            } else {
                showOverlayPermissionDialog();
            }
        } else {
            startFloatingService();
        }
    }

    private void showOverlayPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle("悬浮窗选项")
            .setMessage("选择是否启用悬浮窗显示")
            .setPositiveButton("启用悬浮窗", (dialog, which) -> {
                try {
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                    );
                    mPrefs.edit().putBoolean("float_window_enabled", true).apply();
                    overlayPermissionLauncher.launch(intent);
                } catch (Exception e) {
                    openAppSettings();
                }
            })
            .setNegativeButton("仅后台运行", (dialog, which) -> {
                // ✅ 不启用悬浮窗，仅后台运行
                mPrefs.edit().putBoolean("float_window_enabled", false).apply();
                checkNotificationPermissionAndStart();
            })
            .setNeutralButton("取消", null)
            .setCancelable(false)
            .show();
    }

    private void checkNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    1002);
            } else {
                startFloatingService();
            }
        } else {
            startFloatingService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1002 || requestCode == 1001) {
            startFloatingService();
        }
    }

    private void startFloatingService() {
        try {
            Intent intent = new Intent(this, FloatTimeService.class);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            
            showStatus("✅ 悬浮时间服务已启动");
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
            
            Toast.makeText(this, "悬浮时间已启动", Toast.LENGTH_SHORT).show();
            
            Log.d(TAG, "Service started");
            
        } catch (Exception e) {
            showStatus("❌ 启动失败: " + e.getMessage());
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "Start failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void stopFloatingService() {
        try {
            Intent intent = new Intent(this, FloatTimeService.class);
            stopService(intent);
            
            showStatus("⭕ 服务已停止");
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            
            Toast.makeText(this, "悬浮时间已停止", Toast.LENGTH_SHORT).show();
            
            Log.d(TAG, "Service stopped");
            
        } catch (Exception e) {
            showStatus("❌ 停止失败: " + e.getMessage());
            Log.e(TAG, "Stop failed: " + e.getMessage());
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (Exception e) {
            // 忽略
        }
    }
}
