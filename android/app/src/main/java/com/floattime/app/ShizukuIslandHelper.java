package com.floattime.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import androidx.annotation.NonNull;

import dev.rikka.shizuku.Shizuku;

/**
 * Shizuku 超级岛白名单绕过助手 (UserService 版本)
 *
 * 使用 Shizuku UserService API 执行特权 shell 命令，
 * 绕过小米超级岛焦点通知白名单限制。
 */
public class ShizukuIslandHelper {

    private static final String TAG = "ShizukuIslandHelper";
    private static final String FOCUS_PROVIDER_URI = "content://miui.statusbar.notification.public";
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;

    // UserService 事务码
    private static final int TRANSACTION_EXECUTE = 1;
    private static final int TRANSACTION_DESTROY = 16777115;

    private final Context mContext;
    private final String mPackageName;

    private boolean mShizukuAvailable = false;
    private boolean mShizukuPermissionGranted = false;
    private boolean mWhitelistBypassed = false;

    private IBinder mUserService;
    private boolean mUserServiceBound = false;

    private final Shizuku.OnBinderReceivedListener mBinderReceivedListener =
            () -> {
                Log.d(TAG, "Shizuku binder received");
                checkPermissionAndBindService();
            };

    private final Shizuku.OnBinderDeadListener mBinderDeadListener =
            () -> {
                Log.d(TAG, "Shizuku binder dead");
                mShizukuAvailable = false;
                mUserServiceBound = false;
                mUserService = null;
            };

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

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mUserService = service;
            mUserServiceBound = true;
            Log.d(TAG, "UserService connected");
            tryBypassWhitelist();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mUserService = null;
            mUserServiceBound = false;
            Log.d(TAG, "UserService disconnected");
        }
    };

    public ShizukuIslandHelper(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mPackageName = mContext.getPackageName();
    }

    public void init() {
        Shizuku.addBinderReceivedListener(mBinderReceivedListener);
        Shizuku.addBinderDeadListener(mBinderDeadListener);
        Shizuku.addRequestPermissionResultListener(mPermissionListener);

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

    public void destroy() {
        Shizuku.removeBinderReceivedListener(mBinderReceivedListener);
        Shizuku.removeBinderDeadListener(mBinderDeadListener);
        Shizuku.removeRequestPermissionResultListener(mPermissionListener);

        if (mUserServiceBound && mUserService != null) {
            try {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    mUserService.transact(TRANSACTION_DESTROY, data, reply, 0);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            } catch (Exception e) {
                Log.w(TAG, "Destroy service failed: " + e.getMessage());
            }
            
            try {
                Shizuku.unbindUserService(
                        new Shizuku.UserServiceArgs(
                                new ComponentName(mPackageName, ShellUserService.class.getName()))
                                .tag("floattime-shell"),
                        mServiceConnection,
                        true
                );
            } catch (Exception e) {
                Log.w(TAG, "Unbind failed: " + e.getMessage());
            }
            
            mUserServiceBound = false;
            mUserService = null;
        }
    }

    public boolean isShizukuAvailable() { return mShizukuAvailable; }
    public boolean isPermissionGranted() { return mShizukuPermissionGranted; }
    public boolean isWhitelistBypassed() { return mWhitelistBypassed; }
    public boolean isReady() { return mShizukuAvailable && mShizukuPermissionGranted && mUserServiceBound; }

    private void checkPermissionAndBindService() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            mShizukuPermissionGranted = true;
            bindUserService();
        } else {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
        }
    }

    private void bindUserService() {
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(mPackageName, ShellUserService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("shell_service")
                    .debuggable(false)
                    .version(1)
                    .tag("floattime-shell");

            Shizuku.bindUserService(args, mServiceConnection);
            Log.d(TAG, "UserService bind requested");
        } catch (Exception e) {
            Log.e(TAG, "Bind service failed: " + e.getMessage());
        }
    }

    public void tryBypassWhitelist() {
        if (!mUserServiceBound || mUserService == null) {
            Log.w(TAG, "UserService not ready");
            return;
        }

        new Thread(() -> {
            try {
                Log.d(TAG, "Attempting whitelist bypass for: " + mPackageName);

                String islandSupport = executeShell("getprop persist.sys.feature.island");
                Log.d(TAG, "Island support: " + islandSupport);

                String protocolVer = executeShell("settings get system notification_focus_protocol");
                Log.d(TAG, "Focus protocol version: " + protocolVer);

                boolean registered = registerToWhitelistViaProvider();
                if (registered) {
                    mWhitelistBypassed = true;
                    Log.d(TAG, "✅ Whitelist bypass via provider successful!");
                    return;
                }

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

    private boolean registerToWhitelistViaProvider() {
        try {
            executeShell("content call --uri " + FOCUS_PROVIDER_URI +
                    " --method registerFocusNotification" +
                    " --extra string:package:" + mPackageName +
                    " --extra int:enable:1");

            executeShell("content call --uri " + FOCUS_PROVIDER_URI +
                    " --method grantFocusPermission" +
                    " --extra string:package:" + mPackageName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "ContentProvider registration failed: " + e.getMessage());
            return false;
        }
    }

    private boolean registerViaShell() {
        try {
            String currentList = executeShell("settings get system focus_notification_whitelist");

            if (currentList == null || !currentList.contains(mPackageName)) {
                String newList;
                if (currentList == null || currentList.trim().isEmpty() || "null".equals(currentList.trim())) {
                    newList = mPackageName;
                } else {
                    newList = currentList.trim() + "," + mPackageName;
                }
                executeShell("settings put system focus_notification_whitelist \"" + newList + "\"");
            }

            String verify = executeShell("settings get system focus_notification_whitelist");
            return verify != null && verify.contains(mPackageName);
        } catch (Exception e) {
            Log.e(TAG, "Shell registration failed: " + e.getMessage());
            return false;
        }
    }

    private String executeShell(String command) throws Exception {
        if (mUserService == null) {
            throw new Exception("ShellService not available");
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.floattime.app.IShellService");
            data.writeString(command);
            mUserService.transact(TRANSACTION_EXECUTE, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public boolean checkFocusPermission() {
        try {
            Uri uri = Uri.parse(FOCUS_PROVIDER_URI);
            Bundle extras = new Bundle();
            extras.putString("package", mPackageName);
            Bundle result = mContext.getContentResolver().call(uri, "canShowFocus", null, extras);
            return result != null && result.getBoolean("canShowFocus", false);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isSystemIslandSupported() {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String val = (String) clazz.getMethod("get", String.class).invoke(null, "persist.sys.feature.island");
            return "true".equals(val);
        } catch (Exception e) {
            return false;
        }
    }
}
