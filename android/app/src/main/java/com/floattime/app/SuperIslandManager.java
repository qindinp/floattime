package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 超级岛管理器 v3 (小米 HyperOS 焦点通知)
 *
 * 集成 Shizuku 白名单绕过 + V3 格式焦点通知构建。
 *
 * 参考项目：
 * - Capsulyric (https://github.com/FrancoGiudans/Capsulyric)
 * - HyperNotification (https://github.com/xzakota/HyperNotification)
 *
 * v3 修复：
 * - 通知发送和网络断开改为严格串行（参考 Capsulyric notifyWithNetworkCut）
 * - 先断网 → 发通知 → 等 50ms → 恢复网络，全部同步完成
 */
public class SuperIslandManager {

    private static final String TAG = "SuperIslandManager";

    // 通知渠道 (与 LiveUpdateManager 统一)
    public static final String CHANNEL_ID = FocusParamBuilder.CHANNEL_ID;
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final String CHANNEL_DESC = "实时显示校准时间 (超级岛)";
    public static final int NOTIFICATION_ID = 20240320;

    // 网络断开后保持时间（毫秒），参考 Capsulyric 的 50ms
    private static final long NETWORK_CUT_DURATION_MS = 50L;

    private final Context mContext;
    private final NotificationManager mNotifMgr;
    private final ShizukuIslandHelper mShizukuHelper;

    private boolean mIsHyperOS;
    private static Boolean sCachedIsHyperOS;

    // 缓存: 上一次的 JSON，避免重复构建
    private String mCachedParamJson = "";
    private String mLastTimeStr = "";
    private String mLastMillisStr = "";

    public SuperIslandManager(Context context) {
        mContext = context.getApplicationContext();
        mNotifMgr = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mIsHyperOS = detectHyperOS();
        mShizukuHelper = new ShizukuIslandHelper(mContext);

        createChannel();

        Log.d(TAG, "SuperIslandManager v3 initialized | SDK=" + Build.VERSION.SDK_INT
                + " | Device=" + Build.MANUFACTURER + " " + Build.MANUFACTURER
                + " | HyperOS=" + mIsHyperOS);
    }

    // =============================================
    //  生命周期
    // =============================================

    /**
     * 初始化 Shizuku 连接和白名单绕过
     * 建议在 Service.onCreate() 中调用
     */
    public void init() {
        if (mIsHyperOS) {
            boolean islandSupported = mShizukuHelper.isIslandSupported();
            Log.d(TAG, "Island supported (multi-strategy): " + islandSupported);
            mShizukuHelper.init();
            Log.d(TAG, "Shizuku helper initialized | available=" + mShizukuHelper.isShizukuAvailable()
                    + " | permission=" + mShizukuHelper.isPermissionGranted());
        }
    }

    /**
     * 销毁
     */
    public void destroy() {
        // 退出前恢复 xmsf 网络
        if (mShizukuHelper.isReady()) {
            mShizukuHelper.setXmsfNetworkingSync(true);
        }
        mShizukuHelper.destroy();
        hide();
    }

    // =============================================
    //  状态查询
    // =============================================

    public boolean isHyperOS() {
        return mIsHyperOS;
    }

    /**
     * 是否支持超级岛
     * HyperOS + Android 12+
     */
    public boolean isSupported() {
        return mIsHyperOS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * Shizuku 是否已就绪（可用 + 权限已授予）
     */
    public boolean isShizukuReady() {
        return mShizukuHelper.isShizukuAvailable() && mShizukuHelper.isPermissionGranted();
    }

    /**
     * 白名单是否已绕过
     */
    public boolean isWhitelistBypassed() {
        return mShizukuHelper.isWhitelistBypassed();
    }

    /**
     * Shizuku 助手引用（供 Activity 请求权限时使用）
     */
    public ShizukuIslandHelper getShizukuHelper() {
        return mShizukuHelper;
    }

    // =============================================
    //  公共 API
    // =============================================

    /**
     * 构建焦点通知 JSON 供外部注入（不发送独立通知）
     * 供 LiveUpdateManager 调用，将 miui.focus.param 注入到已有通知中
     *
     * 时序：在 mNotifMgr.notify() 之前调用，通知发送时带断网保护
     */
    public String buildFocusParamJson(String timeStr, String millisStr, String source) {
        if (!isSupported()) return null;
        return buildParamJson(timeStr, millisStr, source);
    }

    /**
     * 显示/更新超级岛通知
     *
     * 核心时序（参考 Capsulyric notifyWithNetworkCut）：
     * 1. 如果 Shizuku 就绪 → 同步断开 xmsf 网络
     * 2. 构建并发送通知
     * 3. 等待 50ms（让系统在断网状态下处理通知）
     * 4. 恢复 xmsf 网络
     */
    public void show(String time, String millis, String source) {
        if (!isSupported()) return;
        try {
            Notification notification = buildNotification(time, millis, source);
            if (notification != null) {
                notifyWithNetworkCut(notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "show failed: " + e.getMessage());
        }
    }

    /**
     * 更新超级岛通知 (与 show 相同)
     */
    public void update(String time, String millis, String source) {
        show(time, millis, source);
    }

    /**
     * 隐藏超级岛通知
     */
    public void hide() {
        mNotifMgr.cancel(NOTIFICATION_ID);
    }

    // =============================================
    //  串行化通知发送（核心修复）
    // =============================================

    /**
     * 发送通知，带可选的 xmsf 网络断开/恢复
     *
     * 参考 Capsulyric SuperIslandHandler.notifyWithNetworkCut():
     *   scope.launch {
     *       XmsfNetworkHelper.setXmsfNetworkingEnabled(context, false)  // ① 断网
     *       manager.notify(...)                                          // ② 发通知
     *       delay(50ms)                                                  // ③ 等待
     *       XmsfNetworkHelper.setXmsfNetworkingEnabled(context, true)    // ④ 恢复
     *   }
     *
     * Java 版实现：同步阻塞当前线程完成以上 4 步
     */
    private void notifyWithNetworkCut(Notification notification) {
        if (mShizukuHelper.isReady()) {
            try {
                // ① 同步断开 xmsf 网络
                Log.d(TAG, "Step 1: Disabling xmsf network...");
                boolean disabled = mShizukuHelper.setXmsfNetworkingSync(false);
                Log.d(TAG, "Step 1 result: " + (disabled ? "OK" : "FAILED"));

                // ② 发送通知
                mNotifMgr.notify(NOTIFICATION_ID, notification);
                Log.d(TAG, "Step 2: Notification sent");

                // ③ 等待 50ms — 给系统时间在断网状态下处理焦点通知
                Thread.sleep(NETWORK_CUT_DURATION_MS);

                // ④ 恢复 xmsf 网络
                mShizukuHelper.setXmsfNetworkingSync(true);
                Log.d(TAG, "Step 4: xmsf network restored");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 恢复网络
                mShizukuHelper.setXmsfNetworkingSync(true);
                Log.w(TAG, "Network cut interrupted, restored network");
            } catch (Exception e) {
                Log.e(TAG, "notifyWithNetworkCut failed: " + e.getMessage());
                // 确保恢复网络
                mShizukuHelper.setXmsfNetworkingSync(true);
            }
        } else {
            // Shizuku 未就绪，直接发送通知
            mNotifMgr.notify(NOTIFICATION_ID, notification);
        }
    }

    // =============================================
    //  V3 焦点通知构建
    // =============================================

    /**
     * 使用 FocusParamBuilder 构建 V3 格式的 miui.focus.param JSON
     */
    private String buildParamJson(String timeStr, String millisStr, String source) {
        // 节流: 时间字符串相同时跳过
        if (timeStr.equals(mLastTimeStr) && millisStr.equals(mLastMillisStr)
                && !mCachedParamJson.isEmpty()) {
            return mCachedParamJson;
        }

        String displayTime = timeStr + millisStr;
        String ticker = displayTime + " | " + source;

        // 计算秒数作为进度（0-59）
        int second = 0;
        try {
            String[] parts = timeStr.split(":");
            if (parts.length >= 3) {
                second = Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) {}

        int progress = (int) ((second / 59.0) * 100);

        FocusParamBuilder builder = new FocusParamBuilder(mContext)
                .setBusiness("float_time")
                .setEnableFloat(true)
                .setUpdatable(true)
                .setIslandFirstFloat(false)
                .setAodTitle(displayTime)
                .setTicker(ticker)
                // chatInfo — 通知栏
                .setChatTitle("校准时间 " + timeStr)
                .setChatContent(source + " | " + millisStr)
                .setChatAppIconPkg(mContext.getPackageName())
                // island — 超级岛展开态
                .setIslandTitle(displayTime)
                .setIslandTextTitle(source)
                .setShowHighlightColor(false)
                // smallIsland — 胶囊形态（秒数进度环）
                .setProgress(progress)
                .setProgressColor("#4CAF50")
                .setProgressColorUnreach("#333333")
                // shareData
                .setShareEnabled(true)
                .setShareTitle("悬浮时间")
                .setShareContent("校准时间 " + displayTime + " (" + source + ")");

        String json = builder.buildParamJson();

        // 更新缓存
        mLastTimeStr = timeStr;
        mLastMillisStr = millisStr;
        mCachedParamJson = json;

        return json;
    }

    /**
     * 构建完整通知
     */
    private Notification buildNotification(String time, String millis, String source) {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                mContext, 0,
                new Intent(mContext, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String timeStr = time.isEmpty() ? "--:--:--" : time;
        String millisStr = millis.isEmpty() ? ".000" : "." + millis;

        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(timeStr + millisStr)
                .setContentText(source)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                ? NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                                : NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
                .build();

        // 注入 V3 焦点通知 JSON
        String json = buildParamJson(timeStr, millisStr, source);
        if (json != null) {
            notification.extras.putString(FocusParamBuilder.KEY_FOCUS_PARAM, json);
        }

        return notification;
    }

    // =============================================
    //  通知渠道
    // =============================================

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(CHANNEL_DESC);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.enableLights(false);
        ch.setSound(null, null);
        ch.setVibrationPattern(null);
        mNotifMgr.createNotificationChannel(ch);
    }

    // =============================================
    //  HyperOS 检测
    // =============================================

    private static synchronized boolean detectHyperOS() {
        if (sCachedIsHyperOS != null) return sCachedIsHyperOS;

        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String prop = (String) clazz.getMethod("get", String.class)
                    .invoke(null, "ro.mi.os.version.incremental");
            if (prop != null && !prop.isEmpty()) {
                Log.d(TAG, "HyperOS version: " + prop);
                sCachedIsHyperOS = true;
                return true;
            }
        } catch (Exception ignored) {}

        String manufacturer = Build.MANUFACTURER.toLowerCase();
        sCachedIsHyperOS = manufacturer.contains("xiaomi")
                || manufacturer.contains("redmi")
                || manufacturer.contains("poco");
        return sCachedIsHyperOS;
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

    /**
     * 检查是否支持超级岛（多策略检测）
     */
    public static boolean isIslandSupportedBySystem() {
        return ShizukuIslandHelper.getHyperOSVersion().startsWith("OS3.")
                || ShizukuIslandHelper.getHyperOSVersion().startsWith("OS2.");
    }
}
