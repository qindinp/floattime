package com.floattime.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 主界面 - 负责权限申请和服务启动
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OVERLAY_PERMISSION = 1001;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1002;
    
    private Button startBtn;
    private Button stopBtn;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        statusText = findViewById(R.id.statusText);

        startBtn.setOnClickListener(v -> checkAndRequestPermissions());
        stopBtn.setOnClickListener(v -> stopFloatingService());
        
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        if (FloatTimeService.isRunning()) {
            statusText.setText("✅ 悬浮时间服务运行中");
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
        } else {
            statusText.setText("⭕ 服务未启动");
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
        }
    }

    /**
     * 检查并申请所有必要权限
     */
    private void checkAndRequestPermissions() {
        // 1. 悬浮窗权限 (必须)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog();
            return;
        }
        
        // 2. 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission();
                return;
            }
        }
        
        // 所有权限已获取，启动服务
        startFloatingService();
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
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
                } catch (Exception e) {
                    // 某些设备可能不支持此 Intent
                    openAppSettings();
                }
            })
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show();
    }

    /**
     * 申请通知权限
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATION_PERMISSION);
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
            
            statusText.setText("✅ 悬浮时间服务已启动");
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
            
        } catch (Exception e) {
            statusText.setText("❌ 启动失败: " + e.getMessage());
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
            
            statusText.setText("⭕ 服务已停止");
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            
        } catch (Exception e) {
            statusText.setText("❌ 停止失败: " + e.getMessage());
        }
    }

    /**
     * 打开应用设置页面
     */
    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // 忽略
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            // 从设置返回，重新检查权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                // 悬浮窗权限已获取，继续检查其他权限
                checkAndRequestPermissions();
            } else {
                statusText.setText("❌ 需要悬浮窗权限才能运行");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            // 无论通知权限是否授予，都可以启动服务（通知权限只是可选的）
            startFloatingService();
        }
    }
}
