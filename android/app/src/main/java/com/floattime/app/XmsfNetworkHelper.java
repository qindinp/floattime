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
 * xmsf (小米推送) 网络控制助手
 *
 * 通过 Shizuku 特权通道临时断开 xmsf 网络，
 * 阻止系统向小米服务器校验焦点通知白名单。
 *
 * 参考: Capsulyric XmsfNetworkHelper
 */
public class XmsfNetworkHelper {

    private static final String TAG = "XmsfNetworkHelper";
    private static final String XMSF_PACKAGE = "com.xiaomi.xmsf";

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
                    .daemon(false)
                    .processNameSuffix("privileged")
                    .version(1);
            Shizuku.bindUserService(sUserServiceArgs, sConnection);
            Log.d(TAG, "Binding Shizuku UserService...");
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
            if (sPrivilegedService == null) {
                Log.w(TAG, "PrivilegedService not connected, attempting bind...");
                bindService(context);
                Thread.sleep(500);
                if (sPrivilegedService == null) {
                    Log.e(TAG, "Still not connected after bind attempt");
                    return false;
                }
            }

            int uid = context.getPackageManager().getPackageUid(XMSF_PACKAGE, 0);
            Log.d(TAG, "Setting xmsf (uid=" + uid + ") networking to " + enabled);

            boolean result = sPrivilegedService.setPackageNetworkingEnabled(uid, enabled);
            Log.d(TAG, (result ? "✅" : "❌") + " xmsf networking " + (enabled ? "restored" : "blocked"));
            return result;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "xmsf package not found");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set xmsf networking: " + e.getMessage());
            return false;
        }
    }
}
