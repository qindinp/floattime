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
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;
import rikka.sui.Sui;

/**
 * Shizuku 超级岛白名单绕过助手 v2
 *
 * 参考 Capsulyric 实现:
 * 1. 同步断网方法 setXmsfNetworkingSync（保证时序正确）
 * 2. binder IPC 移至后台线程 (防 ANR)
 * 3. XmsfNetworkHelper 断开 xmsf 网络绕过白名单
 * 4. 多策略超级岛检测
 * 5. Shizuku 13.x API_V23 权限兼容
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
    private volatile boolean mWhitelistBypassed = false;

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
                Log.d(TAG, "Shizuku binder received");
                mShizukuAvailable = true;
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
                    if (mShizukuPermissionGranted) {
                        tryBypassWhitelist();
                    }
                }
            };

    public ShizukuIslandHelper(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mPackageName = mContext.getPackageName();
    }

    public void init() {
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

        if (Shizuku.getBinder() != null) {
            checkPermissionAsync();
        }
    }

    public void destroy() {
        Shizuku.removeBinderReceivedListener(mBinderReceivedListener);
        Shizuku.removeBinderDeadListener(mBinderDeadListener);
        Shizuku.removeRequestPermissionResultListener(mPermissionListener);
        XmsfNetworkHelper.unbindService();
    }

    private void checkPermissionAsync() {
        sWorkerHandler.post(() -> {
            try {
                if (!Shizuku.pingBinder()) {
                    Log.w(TAG, "Shizuku binder not alive");
                    return;
                }

                boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                mShizukuPermissionGranted = granted;
                Log.d(TAG, "Permission: " + granted);

                if (granted) {
                    XmsfNetworkHelper.bindService(mContext);
                    tryBypassWhitelist();
                } else {
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

    /**
     * 尝试绕过白名单 — 通过 Shizuku 断开 xmsf 网络
     */
    public void tryBypassWhitelist() {
        if (!mShizukuAvailable || !mShizukuPermissionGranted) {
            Log.w(TAG, "Shizuku not ready, skip bypass");
            return;
        }

        sWorkerHandler.post(() -> {
            try {
                boolean result = XmsfNetworkHelper.setXmsfNetworkingEnabled(mContext, false);
                mWhitelistBypassed = result;
                Log.d(TAG, "Bypass attempt: " + (result ? "OK" : "FAILED"));
            } catch (Exception e) {
                Log.e(TAG, "Bypass failed: " + e.getMessage());
                mWhitelistBypassed = false;
            }
        });
    }

    /**
     * 同步断开/恢复 xmsf 网络（阻塞当前线程直到操作完成）
     *
     * 参考 Capsulyric 的 suspend fun setXmsfNetworkingEnabled():
     *   - 在 Shizuku 权限检查通过后执行
     *   - 带重试机制 (MAX_RETRIES=2, RETRY_DELAY=500ms)
     *   - 返回操作结果
     *
     * @param enabled true=恢复网络, false=断开网络
     * @return 操作是否成功
     */
    public boolean setXmsfNetworkingSync(boolean enabled) {
        if (!mShizukuAvailable || !mShizukuPermissionGranted) {
            Log.w(TAG, "Shizuku not ready, cannot set xmsf networking");
            return false;
        }

        AtomicReference<Boolean> resultRef = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        sWorkerHandler.post(() -> {
            try {
                boolean result = XmsfNetworkHelper.setXmsfNetworkingEnabled(mContext, enabled);
                resultRef.set(result);
            } catch (Exception e) {
                Log.e(TAG, "setXmsfNetworkingSync failed: " + e.getMessage());
                resultRef.set(false);
            } finally {
                latch.countDown();
            }
        });

        try {
            if (latch.await(IPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Boolean result = resultRef.get();
                return result != null && result;
            } else {
                Log.e(TAG, "setXmsfNetworkingSync timed out after " + IPC_TIMEOUT_MS + "ms");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 在发送通知前临时断开 xmsf 网络（异步版本，保留兼容）
     * 注意：SuperIslandManager v3 已改用 setXmsfNetworkingSync 实现串行时序
     */
    public void preNotificationHook() {
        if (!mShizukuAvailable || !mShizukuPermissionGranted) return;

        sWorkerHandler.post(() -> {
            try {
                XmsfNetworkHelper.setXmsfNetworkingEnabled(mContext, false);
                sWorkerHandler.postDelayed(() -> {
                    try {
                        XmsfNetworkHelper.setXmsfNetworkingEnabled(mContext, true);
                    } catch (Exception e) {
                        Log.e(TAG, "Restore xmsf network failed: " + e.getMessage());
                    }
                }, 50);
            } catch (Exception e) {
                Log.e(TAG, "preNotificationHook failed: " + e.getMessage());
            }
        });
    }

    public boolean isShizukuAvailable() { return mShizukuAvailable; }
    public boolean isPermissionGranted() { return mShizukuPermissionGranted; }
    public boolean isWhitelistBypassed() { return mWhitelistBypassed; }
    public boolean isReady() { return mShizukuAvailable && mShizukuPermissionGranted; }

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
     * 多策略超级岛支持检测
     */
    public boolean isIslandSupported() {
        // 策略1: 系统属性
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String val = (String) clazz.getMethod("get", String.class)
                    .invoke(null, "persist.sys.feature.island");
            if ("true".equals(val)) return true;
        } catch (Exception ignored) {}

        // 策略2: Settings.System 焦点协议版本 (Capsulyric 方法)
        try {
            int protocol = android.provider.Settings.System.getInt(
                    mContext.getContentResolver(), "notification_focus_protocol", 0);
            if (protocol > 0) return true;
        } catch (Exception ignored) {}

        // 策略3: HyperOS 版本
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String version = (String) clazz.getMethod("get", String.class)
                    .invoke(null, "ro.mi.os.version.incremental");
            if (version != null && (version.startsWith("OS3.") || version.startsWith("OS2."))) {
                return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    public int getFocusProtocolVersion() {
        try {
            return android.provider.Settings.System.getInt(
                    mContext.getContentResolver(), "notification_focus_protocol", 0);
        } catch (Exception e) {
            return 0;
        }
    }

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
