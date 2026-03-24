package com.floattime.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import rikka.shizuku.Shizuku;
import rikka.shizuku.Shizuku.UserServiceArgs;

/**
 * xmsf (小米推送) 网络控制助手 v2
 *
 * 通过 Shizuku 特权通道临时断开 xmsf 网络，
 * 阻止系统向小米服务器校验焦点通知白名单。
 *
 * 参考: Capsulyric XmsfNetworkHelper
 *
 * v2 改进：
 * - UserServiceArgs: daemon(true) + version(2)，与 Capsulyric 一致
 * - 重试机制: MAX_RETRIES=2, RETRY_DELAY=500ms
 * - 更完善的错误处理
 */
public class XmsfNetworkHelper {

    private static final String TAG = "XmsfNetworkHelper";
    private static final String XMSF_PACKAGE = "com.xiaomi.xmsf";
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 500L;

    private static IPrivilegedService sPrivilegedService;
    private static UserServiceArgs sUserServiceArgs;

    private static final ServiceConnection sConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            sPrivilegedService = IPrivilegedService.Stub.asInterface(service);
            Log.d(TAG, "Shizuku UserService connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sPrivilegedService = null;
            Log.d(TAG, "Shizuku UserService disconnected");
        }
    };

    /**
     * 绑定 Shizuku UserService
     */
    public static void bindService(Context context) {
        try {
            sUserServiceArgs = new UserServiceArgs(
                    new ComponentName(context.getPackageName(), PrivilegedServiceImpl.class.getName()))
                    .daemon(true)                        // 保持进程存活，参考 Capsulyric
                    .processNameSuffix("privileged")
                    .debuggable(false)
                    .version(2);                         // version 2，参考 Capsulyric
            Shizuku.bindUserService(sUserServiceArgs, sConnection);
            Log.d(TAG, "Binding Shizuku UserService (daemon=true, version=2)...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind UserService: " + e.getMessage());
        }
    }

    /**
     * 解绑 Shizuku UserService
     */
    public static void unbindService() {
        try {
            if (sUserServiceArgs != null) {
                Shizuku.unbindUserService(sUserServiceArgs, sConnection, true);
            }
            sPrivilegedService = null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to unbind UserService: " + e.getMessage());
        }
    }

    /**
     * 设置 xmsf 网络开关
     *
     * 参考 Capsulyric XmsfNetworkHelper.setXmsfNetworkingEnabled():
     *   - 使用 requireShizukuPermissionGranted 包裹
     *   - MAX_RETRIES = 2, RETRY_DELAY = 500ms
     *   - 捕获 DeadObjectException 并重试
     */
    public static boolean setXmsfNetworkingEnabled(Context context, boolean enabled) {
        try {
            int uid;
            try {
                uid = context.getPackageManager().getPackageUid(XMSF_PACKAGE, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "xmsf package not found (UID lookup failed)");
                return false;
            }

            Log.d(TAG, "setXmsfNetworkingEnabled: enabled=" + enabled + ", uid=" + uid);

            // 带重试的调用（参考 Capsulyric 的 retry loop）
            Exception lastError = null;
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                try {
                    IPrivilegedService service = getOrBindService(context);
                    if (service == null) {
                        Log.w(TAG, "Attempt " + (attempt + 1) + ": PrivilegedService not connected");
                        if (attempt + 1 < MAX_RETRIES) {
                            Thread.sleep(RETRY_DELAY_MS);
                            continue;
                        }
                        return false;
                    }

                    boolean result = service.setPackageNetworkingEnabled(uid, enabled);
                    if (result) {
                        Log.d(TAG, "OK: xmsf networking set to " + enabled);
                        return true;
                    } else {
                        Log.e(TAG, "Privileged service returned failure for uid=" + uid);
                        return false;
                    }
                } catch (android.os.DeadObjectException e) {
                    lastError = e;
                    Log.w(TAG, "DeadObjectException on attempt " + (attempt + 1));
                    sPrivilegedService = null;
                    if (attempt + 1 < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception e) {
                    Log.e(TAG, "Error on attempt " + (attempt + 1) + ": " + e.getMessage());
                    return false;
                }
            }

            Log.e(TAG, "All " + MAX_RETRIES + " attempts failed. Last error: " +
                    (lastError != null ? lastError.getMessage() : "unknown"));
            return false;

        } catch (Exception e) {
            Log.e(TAG, "setXmsfNetworkingEnabled failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取已绑定的 service，如果没有则尝试绑定并等待
     */
    private static IPrivilegedService getOrBindService(Context context) {
        if (sPrivilegedService != null) {
            // 快速 ping 检查连接是否还活着
            try {
                if (sPrivilegedService.asBinder().pingBinder()) {
                    return sPrivilegedService;
                }
            } catch (Exception e) {
                sPrivilegedService = null;
            }
        }

        // 尝试绑定
        Log.w(TAG, "PrivilegedService not connected, attempting bind...");
        bindService(context);

        // 等待连接（最多 2 秒）
        for (int i = 0; i < 20; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (sPrivilegedService != null) {
                try {
                    if (sPrivilegedService.asBinder().pingBinder()) {
                        return sPrivilegedService;
                    }
                } catch (Exception ignored) {}
            }
        }

        Log.e(TAG, "Still not connected after bind attempt");
        return null;
    }
}
