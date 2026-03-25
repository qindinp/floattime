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
 * xmsf (小米推送) 网络控制助手 v3
 *
 * 通过 Shizuku 特权通道临时断开 xmsf 网络，
 * 阻止系统向小米服务器校验焦点通知白名单。
 *
 * 参考: Capsulyric XmsfNetworkHelper
 *
 * v3 改进：
 * - UserServiceArgs: daemon(true) + version(2)，与 Capsulyric 一致
 * - 重试机制: MAX_RETRIES=2, RETRY_DELAY=500ms
 * - getOrBindService 超时从 2s 降到 500ms，避免阻塞通知线程
 * - 更完善的错误处理
 */
public class XmsfNetworkHelper {

    private static final String TAG = "XmsfNetworkHelper";
    private static final String XMSF_PACKAGE = "com.xiaomi.xmsf";
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 500L;

    // getOrBindService 最大等待时间（从 2000ms 降到 500ms）
    private static final long BIND_WAIT_TOTAL_MS = 500L;
    private static final long BIND_WAIT_INTERVAL_MS = 50L;

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
                    .daemon(true)
                    .processNameSuffix("privileged")
                    .debuggable(false)
                    .version(2);
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
     * 获取已绑定的 service，如果没有则尝试绑定并等待（最多 500ms）
     *
     * v3 改进：从 2000ms 降到 500ms，避免阻塞通知发送线程。
     * 如果 500ms 内没连上就快速返回 null，让调用方走降级路径。
     */
    private static IPrivilegedService getOrBindService(Context context) {
        if (sPrivilegedService != null) {
            try {
                if (sPrivilegedService.asBinder().pingBinder()) {
                    return sPrivilegedService;
                }
            } catch (Exception e) {
                sPrivilegedService = null;
            }
        }

        // 尝试绑定
        Log.d(TAG, "PrivilegedService not connected, attempting bind...");
        bindService(context);

        // 等待连接（最多 500ms）
        long waited = 0;
        while (waited < BIND_WAIT_TOTAL_MS) {
            try {
                Thread.sleep(BIND_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            waited += BIND_WAIT_INTERVAL_MS;

            if (sPrivilegedService != null) {
                try {
                    if (sPrivilegedService.asBinder().pingBinder()) {
                        Log.d(TAG, "Service connected after " + waited + "ms");
                        return sPrivilegedService;
                    }
                } catch (Exception ignored) {}
            }
        }

        Log.w(TAG, "Still not connected after " + BIND_WAIT_TOTAL_MS + "ms, giving up");
        return null;
    }
}
