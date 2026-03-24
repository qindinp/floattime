package com.floattime.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import dev.rikka.shizuku.Shizuku;

/**
 * Shizuku 超级岛白名单绕过助手
 *
 * 检测 Shizuku 状态，为后续白名单绕过做准备。
 * 实际的白名单绕过需要：
 * - HyperCeiler (LSPosed 模块)
 * - 或 Root 权限
 */
public class ShizukuIslandHelper {

    private static final String TAG = "ShizukuIslandHelper";
    private static final String FOCUS_PROVIDER_URI = "content://miui.statusbar.notification.public";
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;

    private final Context mContext;
    private final String mPackageName;

    private boolean mShizukuAvailable = false;
    private boolean mShizukuPermissionGranted = false;

    private final Shizuku.OnBinderReceivedListener mBinderReceivedListener =
            () -> {
                Log.d(TAG, "Shizuku binder received");
                mShizukuAvailable = true;
                checkPermission();
            };

    private final Shizuku.OnBinderDeadListener mBinderDeadListener =
            () -> {
                Log.d(TAG, "Shizuku binder dead");
                mShizukuAvailable = false;
            };

    private final Shizuku.OnRequestPermissionResultListener mPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                    mShizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED;
                    Log.d(TAG, "Permission result: " + mShizukuPermissionGranted);
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

        if (Shizuku.getBinder() != null) {
            mShizukuAvailable = true;
            checkPermission();
        }
    }

    public void destroy() {
        Shizuku.removeBinderReceivedListener(mBinderReceivedListener);
        Shizuku.removeBinderDeadListener(mBinderDeadListener);
        Shizuku.removeRequestPermissionResultListener(mPermissionListener);
    }

    private void checkPermission() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            mShizukuPermissionGranted = true;
            Log.d(TAG, "Shizuku permission granted");
        } else {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
        }
    }

    public boolean isShizukuAvailable() { return mShizukuAvailable; }
    public boolean isPermissionGranted() { return mShizukuPermissionGranted; }
    public boolean isWhitelistBypassed() { return false; }
    public boolean isReady() { return mShizukuAvailable && mShizukuPermissionGranted; }

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
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String val = (String) clazz.getMethod("get", String.class).invoke(null, "persist.sys.feature.island");
            return "true".equals(val);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 尝试绕过白名单 (提示用户安装 HyperCeiler)
     */
    public void tryBypassWhitelist() {
        Log.d(TAG, "tryBypassWhitelist: HyperCeiler or Root required for actual bypass");
        // 实际的白名单绕过需要 HyperCeiler 或 Root
        // Shizuku UserService 方式暂不实现，因为复杂度较高
    }
}
