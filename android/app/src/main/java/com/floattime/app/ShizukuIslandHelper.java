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

import dev.rikka.shizuku.Shizuku;
import dev.rikka.shizuku.ShizukuProvider;

/**
 * Shizuku 超级岛白名单绕过助手 (UserService 版本)
 *
 * 使用 Shizuku UserService API 执行特权 shell 命令，
 * 绕过小米超级岛焦点通知白名单限制。
 *
 * 要求：
 * - 用户安装 Shizuku 应用并启动服务
 * - HyperOS 3.0+ 设备
 */
public class ShizukuIslandHelper {

    private static final String TAG = "ShizukuIslandHelper";

    // 小米焦点通知 ContentProvider URI
    private static final String FOCUS_PROVIDER_URI = "content://miui.statusbar.notification.public";

    // Shizuku 权限请求码
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;

    private final Context mContext;
    private final String mPackageName;

    // Shizuku 状态
    private boolean mShizukuAvailable = false;
    private boolean mShizukuPermissionGranted = false;
    private boolean mWhitelistBypassed = false;
    private boolean mUserServiceBound = false;

    // UserService 连接
    private IShellService mShellService;
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mShellService = IShellService.Stub.asInterface(service);
            mUserServiceBound = true;
            Log.d(TAG, "UserService connected");
            // 连接成功后尝试绕过白名单
            tryBypassWhitelist();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mShellService = null;
            mUserServiceBound = false;
            Log.d(TAG, "UserService disconnected");
        }
    };

    // Shizuku Binder 接收监听
    private final Shizuku.OnBinderReceivedListener mBinderReceivedListener =
            () -> {
                Log.d(TAG, "Shizuku binder received");
                checkPermissionAndBindService();
            };

    // Shizuku Binder 死亡监听
    private final Shizuku.OnBinderDeadListener mBinderDeadListener =
            () -> {
                Log.d(TAG, "Shizuku binder dead");
                mShizukuAvailable = false;
                mUserServiceBound = false;
                mShellService = null;
            };

    // Shizuku 权限回调
    private final Shizuku.OnRequestPermissionResultListener mPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                    mShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED;
                    Log.d(TAG, "Permission result: " + mShizukuPermissionGranted);
                    if (mShizukuPermissionGranted) {
                        bindUserService();
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
     * 初始化 Shizuku
     */
    public void init() {
        // 添加监听器
        Shizuku.addBinderReceivedListener(mBinderReceivedListener);
        Shizuku.addBinderDeadListener(mBinderDeadListener);
        Shizuku.addRequestPermissionResultListener(mPermissionListener);

        // 检查 Shizuku 是否可用
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "Shizuku version too old (< v11)");
            return;
        }

        mShizukuAvailable = Shizuku.getBinder() != null;
        Log.d(TAG, "Shizuku available: " + mShizukuAvailable);

        if (mShizukuAvailable) {
            checkPermissionAndBindService();
        }
    }

    /**
     * 销毁
     */
    public void destroy() {
        // 移除监听器
        Shizuku.removeBinderReceivedListener(mBinderReceivedListener);
        Shizuku.removeBinderDeadListener(mBinderDeadListener);
        Shizuku.removeRequestPermissionResultListener(mPermissionListener);

        // 解绑服务
        if (mUserServiceBound) {
            try {
                Shizuku.unbindUserService(
                        new Shizuku.UserServiceArgs(
                                new ComponentName(mPackageName, ShellUserService.class.getName()))
                                .daemon(false)
                                .processNameSuffix("shell_service")
                                .debuggable(false)
                                .version(1)
                                .tag("floattime-shell"),
                        mServiceConnection,
                        true // remove: 清理服务
                );
            } catch (Exception e) {
                Log.w(TAG, "Unbind service failed: " + e.getMessage());
            }
            mUserServiceBound = false;
            mShellService = null;
        }
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

    public boolean isReady() {
        return mShizukuAvailable && mShizukuPermissionGranted && mUserServiceBound;
    }

    // =============================================
    //  权限和服务绑定
    // =============================================

    private void checkPermissionAndBindService() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            mShizukuPermissionGranted = true;
            Log.d(TAG, "Permission already granted");
            bindUserService();
        } else {
            Log.d(TAG, "Requesting permission...");
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
        }
    }

    private void bindUserService() {
        if (!mShizukuPermissionGranted) {
            Log.w(TAG, "Permission not granted, cannot bind service");
            return;
        }

        try {
            Shizuku.bindUserService(
                    new Shizuku.UserServiceArgs(
                            new ComponentName(mPackageName, ShellUserService.class.getName()))
                            .daemon(false)
                            .processNameSuffix("shell_service")
                            .debuggable(false)
                            .version(1)
                            .tag("floattime-shell"),
                    mServiceConnection
            );
            Log.d(TAG, "UserService bind requested");
        } catch (Exception e) {
            Log.e(TAG, "Bind service failed: " + e.getMessage());
        }
    }

    // =============================================
    //  白名单绕过核心逻辑
    // =============================================

    /**
     * 尝试绕过小米超级岛白名单
     */
    public void tryBypassWhitelist() {
        if (!mUserServiceBound || mShellService == null) {
            Log.w(TAG, "UserService not ready");
            return;
        }

        new Thread(() -> {
            try {
                Log.d(TAG, "Attempting whitelist bypass for: " + mPackageName);

                // 步骤 1: 检查超级岛支持
                String islandSupport = executeShell("getprop persist.sys.feature.island");
                Log.d(TAG, "Island support: " + islandSupport);

                // 步骤 2: 获取焦点协议版本
                String protocolVer = executeShell("settings get system notification_focus_protocol");
                Log.d(TAG, "Focus protocol version: " + protocolVer);

                // 步骤 3: 尝试通过 ContentProvider 注册
                boolean registered = registerToWhitelistViaProvider();
                if (registered) {
                    mWhitelistBypassed = true;
                    Log.d(TAG, "✅ Whitelist bypass via provider successful!");
                    return;
                }

                // 步骤 4: 备选方案 - settings 命令
                Log.d(TAG, "Provider method failed, trying shell fallback...");
                boolean shellResult = registerViaShell();
                mWhitelistBypassed = shellResult;

                if (shellResult) {
                    Log.d(TAG, "✅ Whitelist bypass via shell successful!");
                } else {
                    Log.w(TAG, "⚠️ Whitelist bypass may require HyperCeiler or Root");
                }

            } catch (Exception e) {
                Log.e(TAG, "Whitelist bypass failed: " + e.getMessage(), e);
            }
        }).start();
    }

    /**
     * 通过 ContentProvider 注册白名单
     */
    private boolean registerToWhitelistViaProvider() {
        try {
            // 通过 content call 注册
            String result = executeShell(
                    "content call --uri " + FOCUS_PROVIDER_URI +
                    " --method registerFocusNotification" +
                    " --extra string:package:" + mPackageName +
                    " --extra int:enable:1"
            );
            Log.d(TAG, "registerFocusNotification: " + result);

            // 尝试授权
            String grantResult = executeShell(
                    "content call --uri " + FOCUS_PROVIDER_URI +
                    " --method grantFocusPermission" +
                    " --extra string:package:" + mPackageName
            );
            Log.d(TAG, "grantFocusPermission: " + grantResult);

            return true;
        } catch (Exception e) {
            Log.e(TAG, "ContentProvider registration failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 通过 settings 命令注册白名单
     */
    private boolean registerViaShell() {
        try {
            // 读取现有白名单
            String currentList = executeShell("settings get system focus_notification_whitelist");
            Log.d(TAG, "Current whitelist: " + currentList);

            // 添加到白名单
            if (currentList == null || !currentList.contains(mPackageName)) {
                String newList;
                if (currentList == null || currentList.trim().isEmpty() || "null".equals(currentList.trim())) {
                    newList = mPackageName;
                } else {
                    newList = currentList.trim() + "," + mPackageName;
                }

                executeShell("settings put system focus_notification_whitelist \"" + newList + "\"");
                Log.d(TAG, "Updated whitelist: " + newList);
            }

            // 验证
            String verify = executeShell("settings get system focus_notification_whitelist");
            boolean success = verify != null && verify.contains(mPackageName);
            Log.d(TAG, "Whitelist verification: " + success);
            return success;

        } catch (Exception e) {
            Log.e(TAG, "Shell registration failed: " + e.getMessage());
            return false;
        }
    }

    // =============================================
    //  工具方法
    // =============================================

    /**
     * 通过 UserService 执行 shell 命令
     */
    private String executeShell(String command) throws RemoteException {
        if (mShellService == null) {
            throw new RemoteException("ShellService not available");
        }
        return mShellService.execute(command);
    }

    /**
     * 检查焦点通知权限
     */
    public boolean checkFocusPermission() {
        try {
            Uri uri = Uri.parse(FOCUS_PROVIDER_URI);
            Bundle extras = new Bundle();
            extras.putString("package", mPackageName);
            Bundle result = mContext.getContentResolver().call(uri, "canShowFocus", null, extras);
            return result != null && result.getBoolean("canShowFocus", false);
        } catch (Exception e) {
            Log.d(TAG, "checkFocusPermission failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检测超级岛系统支持
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
     * 获取系统属性
     */
    private static String getSystemProperty(String key) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            return (String) clazz.getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            return "";
        }
    }
}
