package com.floattime.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 主界面 - 负责权限申请和服务启动
 */
public class MainActivity extends AppCompatActivity {

    private Button startBtn;
    private Button stopBtn;
    private TextView statusText;
    
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 注册权限结果回调
        overlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        checkNotificationPermission();
                    } else {
                        showStatus("❌ 需要悬浮窗权限才能运行");
                    }
                }
            }
        );
        
        setContentView(R.layout.activity_main);
        
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        statusText = findViewById(R.id.statusText);

        startBtn.setOnClickListener(v -> checkOverlayPermission());
        stopBtn.setOnClickListener(v -> stopFloatingService());
        
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
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

    /**
     * 检查悬浮窗权限
     */
    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                checkNotificationPermission();
            } else {
                showOverlayPermissionDialog();
            }
        } else {
            startFloatingService();
        }
    }

    /**
     * 显示悬浮窗权限说明对话框
     */
    private void showOverlayPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage("悬浮时间需要在其他应用上层显示，请授予悬浮窗权限。")
            .setPositiveButton("去设置", (dialog, which) -> {
                try {
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                    );
                    overlayPermissionLauncher.launch(intent);
                } catch (Exception e) {
                    openAppSettings();
                }
            })
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show();
    }

    /**
     * 检查通知权限 (Android 13+)
     */
    private void checkNotificationPermission() {
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
        if (requestCode == 1002) {
            startFloatingService();
        }
    }

    /**
     * 启动悬浮服务
     */
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
            
        } catch (Exception e) {
            showStatus("❌ 启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 停止悬浮服务
     */
    private void stopFloatingService() {
        try {
            Intent intent = new Intent(this, FloatTimeService.class);
            stopService(intent);
            
            showStatus("⭕ 服务已停止");
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            
            Toast.makeText(this, "悬浮时间已停止", Toast.LENGTH_SHORT).show();
            
        } catch (Exception e) {
            showStatus("❌ 停止失败: " + e.getMessage());
        }
    }

    /**
     * 打开应用设置页面
     */
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
