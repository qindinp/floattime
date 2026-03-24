package com.floattime.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * 超级岛检测助手 (简化版)
 *
 * 注意：小米超级岛有白名单限制，需要以下方式之一绕过：
 * 1. HyperCeiler (LSPosed 模块) — 推荐，无需 Root
 * 2. Root + 修改系统设置
 * 3. 官方合作（需小米开发者认证）
 *
 * 此 Helper 仅用于检测设备状态，不做实际的白名单绕过。
 * Shizuku 的 newProcess API 已废弃，无法直接执行 Shell 命令。
 */
public class ShizukuIslandHelper {

    private static final String TAG = "ShizukuIslandHelper";

    // 小米焦点通知 ContentProvider URI
    private static final String FOCUS_PROVIDER_URI = "content://miui.statusbar.notification.public";

    private final Context mContext;
    private String mPackageName;

    // 状态
    private boolean mWhitelistBypassed = false;
    private boolean mCheckedOnce = false;

    public ShizukuIslandHelper(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mPackageName = mContext.getPackageName();
    }

    // =============================================
    //  生命周期管理
    // =============================================

    /**
     * 初始化检测
     */
    public void init() {
        Log.d(TAG, "ShizukuIslandHelper initialized (detection-only mode)");
        Log.d(TAG, "Note: HyperCeiler or Root required for whitelist bypass");
        checkWhitelistStatus();
    }

    /**
     * 销毁
     */
    public void destroy() {
        // 无需清理
    }

    // =============================================
    //  状态查询
    // =============================================

    public boolean isShizukuAvailable() {
        return false; // 不使用 Shizuku
    }

    public boolean isPermissionGranted() {
        return false; // 不使用 Shizuku
    }

    public boolean isWhitelistBypassed() {
        return mWhitelistBypassed;
    }

    /**
     * 是否已准备好
     */
    public boolean isReady() {
        return mWhitelistBypassed;
    }

    // =============================================
    //  白名单状态检测
    // =============================================

    /**
     * 检测白名单状态
     */
    private void checkWhitelistStatus() {
        if (mCheckedOnce) return;
        mCheckedOnce = true;

        // 尝试通过 ContentProvider 查询权限
        try {
            boolean hasPermission = checkFocusPermission();
            if (hasPermission) {
                mWhitelistBypassed = true;
                Log.d(TAG, "Focus permission already granted");
            } else {
                Log.d(TAG, "Focus permission not granted. Install HyperCeiler for whitelist bypass.");
            }
        } catch (Exception e) {
            Log.d(TAG, "checkFocusPermission failed: " + e.getMessage());
        }

        // 检测超级岛系统支持
        boolean islandSupported = isSystemIslandSupported();
        Log.d(TAG, "System island support: " + islandSupported);

        int protocolVer = getFocusProtocolVersion();
        Log.d(TAG, "Focus protocol version: " + protocolVer);
    }

    /**
     * 检查当前应用是否有焦点通知权限
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
}
