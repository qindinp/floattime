package com.floattime.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;
import rikka.sui.Sui;

/**
 * Shizuku 超级岛白名单绕过助手
 *
 * 修复内容 (参考 Capsulyric/ShizukuUtil + PrivilegedServiceImpl):
 * 1. binder IPC 调用移至后台线程，防止主线程 ANR 卡死
 * 2. 添加 pingBinder() 检查，确保 binder 真正可用
 * 3. 使用 addBinderReceivedListenerSticky 处理已连接场景
 * 4. 超级岛检测改为多策略 (系统属性 + Settings.System + HyperOS 版本)
 * 5. 调用 Sui.init() 初始化 (Capsulyric 要求)
 */
public class ShizukuIslandHelper {

    private static final String TAG = "ShizukuIslandHelper";
    private static final String FOCUS_PROVIDER_URI = "content://miui.statusbar.notification.public";
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;
    private static final long IPC_TIMEOUT_MS = 3000L;

    private final Context mContext;
    private final String mPackageName;

    private volatile boolean mShizukuAvailable = false;
    private volatile boolean mShizukuPermissionGranted = false;

    // 后台线程处理 binder IPC，避免阻塞主线程 (参考 Capsulyric PrivilegedServiceImpl)
    private static final HandlerThread sWorkerThread;
    private static final Handler sWorkerHandler;
    static {
        sWorkerThread = new HandlerThread("ShizukuIPCWorker");
        sWorkerThread.start();
        sWorkerHandler = new Handler(sWorkerThread.getLooper());
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final Shizuku.OnBinderReceivedListener mBinderReceivedListener =
            () -> {
                Log.d(TAG, "Shizuku binder received, checking permission on worker thread...");
                mShizukuAvailable = true;
                // 在后台线程检查权限，避免阻塞主线程
                checkPermissionAsync();
            };

    private final Shizuku.OnBinderDeadListener mBinderDeadListener =
            () -> {
                Log.d(TAG, "Shizuku binder dead");
                mShizukuAvailable = false;
                mShizukuPermissionGranted = false;
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
        // 初始化 Sui (Capsulyric 要求)
        try {
            Sui.init(mPackageName);
        } catch (Exception e) {
            Log.w(TAG, "Sui.init failed: " + e.getMessage());
        }

        Shizuku.addBinderReceivedListenerSticky(mBinderReceivedListener);
        Shizuku.addBinderDeadListener(mBinderDeadListener);
        Shizuku.addRequestPermissionResultListener(mPermissionListener);

        if (Shizuku.isPreV11()) {
            Log.w(TAG, "Shizuku version too old (< v11)");
            return;
        }

        // 安全检查 binder 状态 (非阻塞本地检查)
        if (Shizuku.getBinder() != null) {
            checkPermissionAsync();
        }
    }

    public void destroy() {
        Shizuku.removeBinderReceivedListener(mBinderReceivedListener);
        Shizuku.removeBinderDeadListener(mBinderDeadListener);
        Shizuku.removeRequestPermissionResultListener(mPermissionListener);
    }

    /**
     * 异步检查 Shizuku 权限 — 后台线程 + 超时保护
     * 参考 Capsulyric ShizukuUtil: callbackFlow + pingBinder + checkSelfPermission
     */
    private void checkPermissionAsync() {
        sWorkerHandler.post(() -> {
            try {
                // 1. 先 pingBinder 确认 binder 真正可用 (Capsulyric 模式)
                if (!Shizuku.pingBinder()) {
                    Log.w(TAG, "Shizuku binder not alive, skipping permission check");
                    return;
                }

                // 2. 用 CountDownLatch + timeout 保护 IPC 调用
                AtomicBoolean granted = new AtomicBoolean(false);
                CountDownLatch latch = new CountDownLatch(1);

                sWorkerHandler.post(() -> {
                    try {
                        granted.set(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);
                    } catch (Exception e) {
                        Log.e(TAG, "checkSelfPermission IPC failed: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });

                if (latch.await(IPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    mShizukuPermissionGranted = granted.get();
                    Log.d(TAG, "Permission check completed: " + mShizukuPermissionGranted);
                } else {
                    Log.w(TAG, "Permission check timed out after " + IPC_TIMEOUT_MS + "ms");
                }

                // 如果没有权限，异步请求 (回调在主线程)
                if (!mShizukuPermissionGranted) {
                    mMainHandler.post(() -> {
                        try {
                            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
                        } catch (Exception e) {
                            Log.e(TAG, "requestPermission failed: " + e.getMessage());
                        }
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "checkPermissionAsync failed: " + e.getMessage());
            }
        });
    }

    public boolean isShizukuAvailable() { return mShizukuAvailable; }
    public boolean isPermissionGranted() { return mShizukuPermissionGranted; }
    public boolean isWhitelistBypassed() { return false; }
    public boolean isReady() { return mShizukuAvailable && mShizukuPermissionGranted; }

    /**
     * 检查焦点通知权限 (通过 ContentProvider)
     * 参考 Capsulyric RomUtils.hasFocusPermission()
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
     * 检测超级岛系统支持 — 多策略检测
     *
     * 策略1: persist.sys.feature.island == "true" (MIUI/HyperOS 原生检测)
     * 策略2: Settings.System.notification_focus_protocol > 0 (焦点通知协议版本, Capsulyric 方法)
     * 策略3: HyperOS 版本 >= OS3.0.0 (澎湃OS 3.0 必定支持)
     *
     * 参考 Capsulyric RomUtils.kt 的实现
     */
    public boolean isIslandSupported() {
        // 策略1: 系统属性检测
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String val = (String) clazz.getMethod("get", String.class)
                    .invoke(null, "persist.sys.feature.island");
            if ("true".equals(val)) {
                return true;
            }
        } catch (Exception ignored) {}

        // 策略2: Settings.System 中的焦点通知协议版本 (Capsulyric 使用)
        try {
            int protocolVersion = android.provider.Settings.System.getInt(
                    mContext.getContentResolver(), "notification_focus_protocol", 0);
            if (protocolVersion > 0) {
                return true;
            }
        } catch (Exception ignored) {}

        // 策略3: HyperOS 版本检测 (OS3.x 必定支持超级岛)
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String version = (String) clazz.getMethod("get", String.class)
                    .invoke(null, "ro.mi.os.version.incremental");
            if (version != null && !version.isEmpty()) {
                // 澎湃OS 3.0 = OS3.x = 必定支持超级岛
                if (version.startsWith("OS3.") || version.startsWith("V816")) {
                    return true;
                }
                // HyperOS 2.x 也有基础支持
                if (version.startsWith("OS2.")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * 获取焦点通知协议版本 (Capsulyric 方法)
     */
    public int getFocusProtocolVersion() {
        try {
            return android.provider.Settings.System.getInt(
                    mContext.getContentResolver(), "notification_focus_protocol", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取 HyperOS 版本号
     */
    public static String getHyperOSVersion() {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String prop = (String) clazz.getMethod("get", String.class)
                    .invoke(null, "ro.mi.os.version.name");
            if (prop != null && !prop.isEmpty()) return prop;

            prop = (String) clazz.getMethod("get", String.class)
                    .invoke(null, "ro.mi.os.version.incremental");
            if (prop != null && !prop.isEmpty()) return prop;
        } catch (Exception ignored) {}
        return "Unknown";
    }
}
