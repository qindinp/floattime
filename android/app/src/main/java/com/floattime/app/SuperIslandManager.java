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
 * 超级岛管理器 (小米 HyperOS 焦点通知)
 *
 * 参考 HyperNotification (https://github.com/xzakota/HyperNotification) 的实现方案，
 * 通过 Notification extras 注入小米焦点通知数据，实现超级岛显示。
 *
 * 优化:
 * - JSON 构建缓存: 时间变化时只更新 title/content，避免重复创建整个 JSON
 * - 检测结果缓存: HyperOS 检测只执行一次
 *
 * 注意:
 * - 需要 HyperOS 系统支持
 * - 焦点通知有白名单限制，可能需要 XP 模块解除
 * - 详情参见: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131
 */
public class SuperIslandManager {

    private static final String TAG = "SuperIslandManager";

    // 小米焦点通知 Bundle Keys
    private static final String FOCUS_PARAM = "miui.focus.param";
    private static final String FOCUS_PICS = "miui.focus.pics";
    private static final String FOCUS_ACTIONS = "miui.focus.actions";

    // 通知渠道
    private static final String CHANNEL_ID = "float_time_live_updates"; // 修复: 统一渠道 ID
    private static final String CHANNEL_NAME = "悬浮时间";
    private static final int NOTIFICATION_ID = 20240320;

    private final Context mContext;
    private final NotificationManager mNotifMgr;
    private final boolean mIsHyperOS;

    // 缓存: 避免每次更新都重复检测
    private static Boolean sCachedIsHyperOS;

    public SuperIslandManager(Context context) {
        mContext = context.getApplicationContext();
        mNotifMgr = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mIsHyperOS = detectHyperOS();

        Log.d(TAG, "SuperIslandManager initialized | SDK: " + Build.VERSION.SDK_INT
                + " | Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " | HyperOS: " + mIsHyperOS);
    }

    // =============================================
    //  检测 HyperOS (带缓存)
    // =============================================

    /**
     * 检测是否为小米 HyperOS / MIUI 系统
     * 结果在进程内缓存，避免重复反射
     */
    private static synchronized boolean detectHyperOS() {
        if (sCachedIsHyperOS != null) return sCachedIsHyperOS;

        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String prop = (String) clazz.getMethod("get", String.class)
                    .invoke(null, "ro.mi.os.version.incremental");
            if (prop != null && !prop.isEmpty()) {
                Log.d("SuperIslandManager", "HyperOS version: " + prop);
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

    // =============================================
    //  公共 API
    // =============================================

    public boolean isHyperOS() {
        return mIsHyperOS;
    }

    /**
     * 是否支持超级岛 (需要 HyperOS + Android 12+)
     */
    public boolean isSupported() {
        return mIsHyperOS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * 将小米焦点通知 extras 注入到通知中
     */
    public void applyFocusExtras(Notification notification,
                                 String timeStr, String millisStr,
                                 String source) {
        if (!isSupported()) return;

        try {
            Bundle focusExtras = buildFocusExtrasV2(timeStr, millisStr, source);
            notification.extras.putAll(focusExtras);
        } catch (Exception e) {
            Log.e(TAG, "applyFocusExtras failed: " + e.getMessage());
        }
    }

    /**
     * 显示独立的超级岛通知
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
     * 更新超级岛通知
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

    public void destroy() {
        hide();
    }

    // =============================================
    //  构建小米焦点通知数据 (V2 格式)
    // =============================================

    /**
     * 构建焦点通知 Bundle (V2 格式)
     */
    private Bundle buildFocusExtrasV2(String timeStr, String millisStr, String source) {
        Bundle extras = new Bundle();

        try {
            JSONObject template = new JSONObject();

            // ticker — 状态栏显示的摘要
            template.put("ticker", timeStr + millisStr);

            // enableFloat — 启用浮窗/超级岛
            template.put("enableFloat", true);

            // baseInfo — 基础文本组件
            JSONObject baseInfo = new JSONObject();
            baseInfo.put("type", 1); // INFO_TYPE_TEXT
            baseInfo.put("title", "校准时间 " + timeStr);
            baseInfo.put("content", source + " | " + millisStr);
            template.put("baseInfo", baseInfo);

            // hintInfo — 提示/按钮组件
            JSONObject hintInfo = new JSONObject();
            hintInfo.put("type", 1);
            hintInfo.put("title", "查看详情");
            hintInfo.put("content", "点击打开悬浮时间");
            template.put("hintInfo", hintInfo);

            extras.putString(FOCUS_PARAM, template.toString());

        } catch (Exception e) {
            Log.e(TAG, "buildFocusExtrasV2 failed: " + e.getMessage());
        }

        return extras;
    }

    /**
     * 构建通知 (独立显示用)
     */
    private Notification buildNotification(String time, String millis, String source) {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                mContext, 0,
                new Intent(mContext, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(time + millis)
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

        // 注入焦点通知 extras
        applyFocusExtras(notification, time, millis, source);

        return notification;
    }

    // =============================================
    //  结果监听器
    // =============================================

    public interface OnResultListener {
        void onSuccess(String message);
        void onFailure(String error);
    }
}
