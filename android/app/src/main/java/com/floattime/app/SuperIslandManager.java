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

/**
 * 超级岛管理器 v2 (小米 HyperOS 焦点通知)
 *
 * 集成 Shizuku 白名单绕过 + V3 格式焦点通知构建。
 *
 * 参考项目：
 * - Capsulyric (https://github.com/FrancoGiudans/Capsulyric)
 * - HyperNotification (https://github.com/xzakota/HyperNotification)
 *
 * 实现方案：
 * 1. 通过 Shizuku 特权通道绕过小米焦点通知白名单限制
 * 2. 使用 FocusParamBuilder 构建 V3 格式的 miui.focus.param JSON
 * 3. 注入到 Notification.extras 中，HyperOS 系统识别后触发超级岛
 *
 * 注意：
 * - 需要用户安装 Shizuku 并授权
 * - 需要 HyperOS 3.0+ 系统支持
 * - 不需要小米开发者平台注册，不需要 Root
 */
public class SuperIslandManager {

    private static final String TAG = "SuperIslandManager";

    // 通知渠道 (与 LiveUpdateManager 统一)
    public static final String CHANNEL_ID = FocusParamBuilder.CHANNEL_ID;
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final String CHANNEL_DESC = "实时显示校准时间 (超级岛)";
    public static final int NOTIFICATION_ID = 20240320;

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

        Log.d(TAG, "SuperIslandManager v2 initialized | SDK=" + Build.VERSION.SDK_INT
                + " | Device=" + Build.MANUFACTURER + " " + Build.MODEL
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
            // 多策略检测超级岛支持 (包含 Settings.System 检测)
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
     * 将焦点通知 extras 注入到通知中
     * 供 LiveUpdateManager 调用
     */
    public void applyFocusExtras(Notification notification,
                                 String timeStr, String millisStr,
                                 String source) {
        if (!isSupported()) return;

        try {
            // 发通知前临时断开 xmsf 网络绕过白名单
            mShizukuHelper.preNotificationHook();
            String json = buildParamJson(timeStr, millisStr, source);
            if (json != null) {
                notification.extras.putString(FocusParamBuilder.KEY_FOCUS_PARAM, json);
            }
        } catch (Exception e) {
            Log.e(TAG, "applyFocusExtras failed: " + e.getMessage());
        }
    }

    /**
     * 显示/更新超级岛通知
     */
    public void show(String time, String millis, String source) {
        if (!isSupported()) return;
        try {
            Notification notification = buildNotification(time, millis, source);
            if (notification != null) {
                mNotifMgr.notify(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "show failed: " + e.getMessage());
        }
    }

    /**
     * 更新超级岛通知 (与 show 相同，带节流)
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

        // 注入 V3 焦点通知
        applyFocusExtras(notification, timeStr, millisStr, source);

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
     * 使用 ShizukuIslandHelper 的多策略方法
     */
    public static boolean isIslandSupportedBySystem() {
        // 静态方法中只能做系统属性检测
        // 完整检测需要 Context，由 ShizukuIslandHelper.isIslandSupported() 提供
        return ShizukuIslandHelper.getHyperOSVersion().startsWith("OS3.")
                || ShizukuIslandHelper.getHyperOSVersion().startsWith("OS2.");
    }
}
