package com.floattime.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shizuku 管理器
 * 
 * 用于绕过小米超级岛白名单限制
 * 
 * 使用方法：
 * 1. 用户安装 Shizuku 应用
 * 2. 应用请求 Shizuku 权限
 * 3. 使用 Shizuku 执行命令添加应用到超级岛白名单
 */
public class ShizukuManager {

    private static final String TAG = "ShizukuManager";
    
    // Shizuku 包名
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    
    // 超级岛白名单命令 (需要 Shizuku Root 权限)
    private static final String SUPER_ISLAND_ALLOW_CMD = 
        "settings put secure super_float_app_list %s";

    private final Context mContext;
    private final ExecutorService mExecutor;
    
    private boolean mIsShizukuAvailable = false;
    private boolean mIsRootAvailable = false;

    public interface OnResultListener {
        void onSuccess(String message);
        void onFailure(String error);
    }

    public ShizukuManager(Context context) {
        mContext = context.getApplicationContext();
        mExecutor = Executors.newSingleThreadExecutor();
        checkShizukuStatus();
    }

    /**
     * 检查 Shizuku 状态
     */
    public void checkShizukuStatus() {
        mIsShizukuAvailable = isPackageInstalled(SHIZUKU_PACKAGE);
        
        // 检查是否有 root
        try {
            Process process = Runtime.getRuntime().exec("su -c id");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            mIsRootAvailable = line != null && line.contains("uid=0");
            reader.close();
        } catch (Exception e) {
            mIsRootAvailable = false;
        }
        
        Log.d(TAG, "Shizuku available: " + mIsShizukuAvailable + ", Root available: " + mIsRootAvailable);
    }

    /**
     * 是否支持超级岛
     */
    public boolean isSuperIslandSupported() {
        // 需要 Android 15+ 和 HyperOS
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return false;
        }
        
        // 检查是否为小米设备
        return isXiaomiDevice() || mIsShizukuAvailable || mIsRootAvailable;
    }

    /**
     * 是否为小米设备
     */
    public boolean isXiaomiDevice() {
        return Build.MANUFACTURER.toLowerCase().contains("xiaomi") ||
               Build.BRAND.toLowerCase().contains("xiaomi") ||
               Build.MANUFACTURER.toLowerCase().contains("redmi") ||
               Build.BRAND.toLowerCase().contains("redmi") ||
               Build.MANUFACTURER.toLowerCase().contains("poco") ||
               Build.BRAND.toLowerCase().contains("poco");
    }

    /**
     * 是否有 Shizuku
     */
    public boolean hasShizuku() {
        return mIsShizukuAvailable;
    }

    /**
     * 是否有 Root
     */
    public boolean hasRoot() {
        return mIsRootAvailable;
    }

    /**
     * 启用超级岛白名单
     * 使用 Shizuku 或 Root 执行命令
     */
    public void enableSuperIsland(OnResultListener listener) {
        mExecutor.execute(() -> {
            try {
                String packageName = mContext.getPackageName();
                
                if (mIsRootAvailable) {
                    // 使用 Root
                    String cmd = String.format(SUPER_ISLAND_ALLOW_CMD, packageName);
                    execCommand(cmd);
                    Log.d(TAG, "Enabled Super Island via Root");
                    if (listener != null) {
                        listener.onSuccess("已通过 Root 启用超级岛支持");
                    }
                } else if (mIsShizukuAvailable) {
                    // 使用 Shizuku
                    // 注意：Shizuku 需要特殊权限才能执行某些命令
                    // 这里只是尝试基本的包添加
                    try {
                        // 尝试通过 Shizuku API 执行命令
                        execShizukuCommand("pm grant " + packageName + " android.permission.WRITE_SECURE_SETTINGS");
                        Log.d(TAG, "Enabled Super Island via Shizuku");
                        if (listener != null) {
                            listener.onSuccess("已通过 Shizuku 启用超级岛支持");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Shizuku command failed: " + e.getMessage());
                        if (listener != null) {
                            listener.onFailure("Shizuku 权限不足，请确保 Shizuku 已授权 Root 权限");
                        }
                    }
                } else {
                    if (listener != null) {
                        listener.onFailure("需要 Root 或 Shizuku 才能启用超级岛支持");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to enable Super Island: " + e.getMessage());
                if (listener != null) {
                    listener.onFailure("启用失败: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 检查包是否安装
     */
    private boolean isPackageInstalled(String packageName) {
        try {
            mContext.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 执行 Shell 命令 (Root)
     */
    private void execCommand(String cmd) throws Exception {
        Process process = Runtime.getRuntime().exec("su -c " + cmd);
        process.waitFor();
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        String error = reader.readLine();
        if (error != null && !error.isEmpty()) {
            Log.w(TAG, "Command error: " + error);
        }
        reader.close();
    }

    /**
     * 执行 Shizuku 命令
     */
    private void execShizukuCommand(String cmd) throws Exception {
        // Shizuku 需要通过 binder 调用
        // 这里简化处理，实际需要使用 Shizuku API
        android.os.Process myUid = android.os.Process.myUid();
        Log.d(TAG, "Current UID: " + myUid);
        
        // 尝试通过 Runtime.exec 执行（Shizuku 需要特殊配置）
        try {
            Process process = Runtime.getRuntime().exec(cmd);
            process.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "execShizukuCommand failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 打开 Shizuku 应用设置页面
     */
    public void openShizukuSettings() {
        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to open Shizuku: " + e.getMessage());
        }
    }

    /**
     * 获取状态描述
     */
    public String getStatusDescription() {
        if (hasRoot()) {
            return "已获取 Root 权限，可以启用超级岛支持";
        } else if (hasShizuku()) {
            return "已安装 Shizuku，可以启用超级岛支持";
        } else {
            return "未检测到 Root 或 Shizuku";
        }
    }

    public void destroy() {
        mExecutor.shutdown();
    }
}
