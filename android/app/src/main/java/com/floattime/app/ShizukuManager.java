package com.floattime.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 管理器
 *
 * 用于绕过小米超级岛白名单限制
 *
 * 修复: execShizukuCommand 之前只是 Runtime.exec()，未走 Shizuku binder。
 * 现在使用 Shizuku.newProcess() 通过 binder 执行特权命令。
 */
public class ShizukuManager {

    private static final String TAG = "ShizukuManager";

    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String SETTING_SUPER_FLOAT_LIST = "super_float_app_list";
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 10086;

    private final Context mContext;
    private final ExecutorService mExecutor;

    private boolean mIsShizukuInstalled = false;
    private boolean mIsShizukuRunning = false;
    private boolean mIsShizukuPermissionGranted = false;
    private boolean mIsRootAvailable = false;

    public interface OnResultListener {
        void onSuccess(String message);
        void onFailure(String error);
    }

    public ShizukuManager(Context context) {
        mContext = context.getApplicationContext();
        mExecutor = Executors.newSingleThreadExecutor();
        checkShizukuStatus();
    }

    /**
     * 检查 Shizuku + Root 状态
     */
    public void checkShizukuStatus() {
        mIsShizukuInstalled = isPackageInstalled(SHIZUKU_PACKAGE);

        // Shizuku 是否运行中
        if (mIsShizukuInstalled) {
            try {
                mIsShizukuRunning = Shizuku.ping();
            } catch (Exception e) {
                mIsShizukuRunning = false;
            }
        }

        // Shizuku 权限
        if (mIsShizukuRunning) {
            try {
                mIsShizukuPermissionGranted = Shizuku.checkSelfPermission()
                        == PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) {
                mIsShizukuPermissionGranted = false;
            }
        }

        // Root
        try {
            Process process = Runtime.getRuntime().exec("su -c id");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            mIsRootAvailable = line != null && line.contains("uid=0");
            reader.close();
        } catch (Exception e) {
            mIsRootAvailable = false;
        }

        Log.d(TAG, "Status: shizuku_installed=" + mIsShizukuInstalled
                + ", running=" + mIsShizukuRunning
                + ", permission=" + mIsShizukuPermissionGranted
                + ", root=" + mIsRootAvailable);
    }

    public boolean isXiaomiDevice() {
        String mfr = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        return mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco")
                || brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco");
    }

    public boolean isSuperIslandSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return false;
        }
        return isXiaomiDevice() && (mIsShizukuPermissionGranted || mIsRootAvailable);
    }

    public boolean hasShizuku() {
        return mIsShizukuInstalled && mIsShizukuRunning;
    }

    public boolean hasShizukuPermission() {
        return mIsShizukuPermissionGranted;
    }

    public boolean hasRoot() {
        return mIsRootAvailable;
    }

    /**
     * 请求 Shizuku 权限（需在 Activity 中调用）
     */
    public void requestShizukuPermission() {
        if (!mIsShizukuRunning) return;
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Request permission failed: " + e.getMessage());
        }
    }

    /**
     * 启用超级岛白名单
     *
     * 优先 Shizuku binder → 回退 Root su
     */
    public void enableSuperIsland(OnResultListener listener) {
        mExecutor.execute(() -> {
            String packageName = mContext.getPackageName();
            String cmd = "settings put secure " + SETTING_SUPER_FLOAT_LIST + " " + packageName;

            // ── 优先 Shizuku ──
            if (mIsShizukuPermissionGranted) {
                try {
                    boolean ok = execViaShizuku(cmd);
                    if (ok) {
                        Log.d(TAG, "Enabled via Shizuku");
                        notify(listener, true, "已通过 Shizuku 启用超级岛支持");
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Shizuku failed: " + e.getMessage());
                }
            }

            // ── 回退 Root ──
            if (mIsRootAvailable) {
                try {
                    Process p = Runtime.getRuntime().exec("su -c " + cmd);
                    int exit = p.waitFor();
                    if (exit == 0) {
                        Log.d(TAG, "Enabled via Root");
                        notify(listener, true, "已通过 Root 启用超级岛支持");
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Root failed: " + e.getMessage());
                }
            }

            notify(listener, false, "需要 Shizuku 授权或 Root 权限");
        });
    }

    /**
     * ✅ 核心修复: 通过 Shizuku binder 执行命令
     *
     * 之前: Runtime.exec(cmd) → 没有权限，执行失败
     * 现在: Shizuku.newProcess() → 通过 binder 在 Shizuku 进程中执行
     */
    private boolean execViaShizuku(String command) throws Exception {
        Log.d(TAG, "Executing via Shizuku: " + command);

        Process process = Shizuku.newProcess(
                new String[]{"sh", "-c", command},
                null, null
        );

        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            Log.e(TAG, "Shizuku command timeout");
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
            String errLine = errReader.readLine();
            errReader.close();
            Log.w(TAG, "Shizuku stderr: " + errLine);
        }

        return exitCode == 0;
    }

    /**
     * 打开 Shizuku 应用
     */
    public void openShizuku() {
        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Open Shizuku failed: " + e.getMessage());
        }
    }

    public String getStatusDescription() {
        if (mIsRootAvailable) {
            return "已获取 Root 权限，可以启用超级岛";
        } else if (mIsShizukuPermissionGranted) {
            return "Shizuku 已授权，可以启用超级岛";
        } else if (mIsShizukuRunning) {
            return "Shizuku 运行中，请授权";
        } else if (mIsShizukuInstalled) {
            return "Shizuku 已安装，请启动";
        } else {
            return "请安装 Shizuku 或获取 Root";
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            mContext.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void notify(OnResultListener l, boolean success, String msg) {
        if (l == null) return;
        if (success) l.onSuccess(msg);
        else l.onFailure(msg);
    }

    public void destroy() {
        mExecutor.shutdown();
    }
}
