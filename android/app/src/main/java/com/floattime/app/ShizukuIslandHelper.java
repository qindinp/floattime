package com.floattime.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import dev.rikka.shizuku.Shizuku;
import dev.rikka.shizuku.ShizukuRemoteProcess;
import dev.rikka.shizuku.ShizukuUserServiceArgs;

/**
 * Shizuku 超级岛白名单绕过助手
 *
 * 参考 Capsulyric 的 Shizuku 方案，通过 Shizuku 特权通道执行以下操作：
 * 1. 检测设备超级岛支持状态
 * 2. 查询/授予焦点通知权限
 * 3. 通过 ContentProvider 将包名加入白名单
 *
 * 使用方式：
 * - 用户安装 Shizuku 应用并启动服务（adb 或无线调试）
 * - 本应用请求 Shizuku 权限
 * - 获得权限后，通过 Shizuku 执行特权操作绕过白名单
 */
public class ShizukuIslandHelper {

    private static final String TAG = "ShizukuIslandHelper";

    // 小米焦点通知 ContentProvider URI
    private static final String FOCUS_PROVIDER_URI = "content://miui.statusbar.notification.public";

    private final Context mContext;
    private String mPackageName;

    // Shizuku 状态
    private boolean mShizukuAvailable = false;
    private boolean mShizukuPermissionGranted = false;
    private boolean mWhitelistBypassed = false;

    // Shizuku 权限请求码
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;

    // Shizuku 权限回调
    private final Shizuku.OnRequestPermissionResultListener mPermissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                        mShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED;
                        Log.d(TAG, "Shizuku permission result: " + mShizukuPermissionGranted);
                        if (mShizukuPermissionGranted) {
                            tryBypassWhitelist();
                        }
                    }
                }
            };

    public ShizukuIslandHelper(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mPackageName = mContext.getPackageName();
    }

    // =============================================
    //  生命周期管理
    // =============================================

    /**
     * 初始化 Shizuku，检测可用性并请求权限
     * 建议在 Activity.onCreate() 或 Service.onCreate() 中调用
     */
    public void init() {
        Shizuku.addRequestPermissionResultListener(mPermissionListener);

        if (!isShizukuInstalled()) {
            Log.w(TAG, "Shizuku is not installed");
            mShizukuAvailable = false;
            return;
        }

        if (Shizuku.isPreV11()) {
            Log.w(TAG, "Shizuku version too old (< v11)");
            mShizukuAvailable = false;
            return;
        }

        mShizukuAvailable = true;
        Log.d(TAG, "Shizuku available, version: " + Shizuku.getVersion());

        // 检查权限
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            mShizukuPermissionGranted = true;
            Log.d(TAG, "Shizuku permission already granted");
            tryBypassWhitelist();
        } else {
            Log.d(TAG, "Requesting Shizuku permission...");
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
        }
    }

    /**
     * 销毁，移除监听器
     */
    public void destroy() {
        Shizuku.removeRequestPermissionResultListener(mPermissionListener);
    }

    // =============================================
    //  状态查询
    // =============================================

    public boolean isShizukuAvailable() {
        return mShizukuAvailable;
    }

    public boolean isPermissionGranted() {
        return mShizukuPermissionGranted;
    }

    public boolean isWhitelistBypassed() {
        return mWhitelistBypassed;
    }

    /**
     * 是否已准备好（Shizuku 可用 + 权限已授予 + 白名单已绕过）
     */
    public boolean isReady() {
        return mShizukuAvailable && mShizukuPermissionGranted && mWhitelistBypassed;
    }

    // =============================================
    //  白名单绕过核心逻辑
    // =============================================

    /**
     * 尝试绕过小米超级岛白名单
     * 使用 Shizuku 特权调用小米系统 ContentProvider
     */
    public void tryBypassWhitelist() {
        if (!mShizukuPermissionGranted) {
            Log.w(TAG, "Shizuku permission not granted, cannot bypass");
            return;
        }

        new Thread(() -> {
            try {
                Log.d(TAG, "Attempting whitelist bypass for: " + mPackageName);

                // 步骤 1: 检查是否支持超级岛
                String islandSupport = execShizukuShell("getprop persist.sys.feature.island");
                Log.d(TAG, "Island support: " + islandSupport);
                if (!"true".equals(islandSupport.trim())) {
                    Log.w(TAG, "Device does not support island feature");
                }

                // 步骤 2: 获取焦点通知协议版本
                String protocolVer = execShizukuShell(
                        "settings get system notification_focus_protocol");
                Log.d(TAG, "Focus protocol version: " + protocolVer);

                // 步骤 3: 通过 ContentProvider 注册白名单
                // 参考 Capsulyric 的 hasFocusPermission() 方式，
                // 但通过 Shizuku 以 shell 身份调用，绕过普通应用的权限限制
                boolean registered = registerToWhitelistViaProvider();
                if (registered) {
                    mWhitelistBypassed = true;
                    Log.d(TAG, "✅ Whitelist bypass successful!");
                } else {
                    // 备选方案：直接通过 settings 命令添加
                    Log.d(TAG, "Provider method failed, trying shell fallback...");
                    boolean shellResult = registerViaShell();
                    mWhitelistBypassed = shellResult;
                    if (shellResult) {
                        Log.d(TAG, "✅ Whitelist bypass via shell successful!");
                    } else {
                        Log.w(TAG, "⚠️ Whitelist bypass may require HyperCeiler (Root)");
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Whitelist bypass failed: " + e.getMessage(), e);
            }
        }).start();
    }

    /**
     * 方式 1: 通过小米 ContentProvider 注册白名单
     * 调用 miui.statusbar.notification.public 的 registerFocusNotification 接口
     */
    private boolean registerToWhitelistViaProvider() {
        try {
            // 使用 Shizuku shell 调用 content provider
            // am startservice / broadcast 通过系统 API 注册
            String result = execShizukuShell(
                    "content call --uri " + FOCUS_PROVIDER_URI +
                    " --method registerFocusNotification" +
                    " --extra string:package:" + mPackageName +
                    " --extra int:enable:1"
            );
            Log.d(TAG, "registerFocusNotification result: " + result);

            // 也尝试 grantFocusPermission
            String grantResult = execShizukuShell(
                    "content call --uri " + FOCUS_PROVIDER_URI +
                    " --method grantFocusPermission" +
                    " --extra string:package:" + mPackageName
            );
            Log.d(TAG, "grantFocusPermission result: " + grantResult);

            return true;
        } catch (Exception e) {
            Log.e(TAG, "ContentProvider registration failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 方式 2: 通过 settings 命令注册白名单（备选方案）
     * 将包名写入系统设置中的白名单字段
     */
    private boolean registerViaShell() {
        try {
            // 读取现有白名单
            String currentList = execShizukuShell(
                    "settings get system focus_notification_whitelist");
            Log.d(TAG, "Current whitelist: " + currentList);

            // 如果白名单中没有本应用，添加之
            if (currentList == null || !currentList.contains(mPackageName)) {
                String newList;
                if (currentList == null || currentList.trim().isEmpty() || "null".equals(currentList.trim())) {
                    newList = mPackageName;
                } else {
                    newList = currentList.trim() + "," + mPackageName;
                }
                String setResult = execShizukuShell(
                        "settings put system focus_notification_whitelist \"" + newList + "\"");
                Log.d(TAG, "Updated whitelist: " + newList + " | result: " + setResult);
            }

            // 再次确认
            String verify = execShizukuShell(
                    "settings get system focus_notification_whitelist");
            boolean success = verify != null && verify.contains(mPackageName);
            Log.d(TAG, "Whitelist verification: " + success);
            return success;

        } catch (Exception e) {
            Log.e(TAG, "Shell registration failed: " + e.getMessage());
            return false;
        }
    }

    // =============================================
    //  系统状态查询
    // =============================================

    /**
     * 检查当前应用是否有焦点通知权限
     * 参考 Capsulyric 的 hasFocusPermission() 实现
     */
    public boolean checkFocusPermission() {
        try {
            Uri uri = Uri.parse(FOCUS_PROVIDER_URI);
            Bundle extras = new Bundle();
            extras.putString("package", mPackageName);
            Bundle result = mContext.getContentResolver().call(uri, "canShowFocus", null, extras);
            return result != null && result.getBoolean("canShowFocus", false);
        } catch (Exception e) {
            Log.d(TAG, "checkFocusPermission via provider failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取超级岛支持状态（系统属性）
     */
    public boolean isSystemIslandSupported() {
        try {
            String val = getSystemProperty("persist.sys.feature.island");
            return "true".equals(val);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取焦点通知协议版本
     */
    public int getFocusProtocolVersion() {
        try {
            return android.provider.Settings.System.getInt(
                    mContext.getContentResolver(),
                    "notification_focus_protocol", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // =============================================
    //  工具方法
    // =============================================

    /**
     * 检查 Shizuku 是否已安装
     */
    private boolean isShizukuInstalled() {
        try {
            mContext.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 通过反射获取系统属性
     */
    private static String getSystemProperty(String key) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            return (String) clazz.getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 通过 Shizuku 执行 Shell 命令
     * 获取 Shizuku 的特权 Shell 权限执行命令
     */
    private String execShizukuShell(String command) throws Exception {
        if (!mShizukuPermissionGranted) {
            throw new IllegalStateException("Shizuku permission not granted");
        }

        Process process = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, null);
        ShizukuRemoteProcess remoteProcess = (ShizukuRemoteProcess) process;

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(remoteProcess.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        BufferedReader errReader = new BufferedReader(
                new InputStreamReader(remoteProcess.getErrorStream()));
        StringBuilder errOutput = new StringBuilder();
        while ((line = errReader.readLine()) != null) {
            errOutput.append(line).append("\n");
        }

        int exitCode = remoteProcess.waitFor();

        reader.close();
        errReader.close();

        if (exitCode != 0 && errOutput.length() > 0) {
            Log.w(TAG, "Shell command stderr: " + errOutput.toString().trim());
        }

        return output.toString().trim();
    }
}
