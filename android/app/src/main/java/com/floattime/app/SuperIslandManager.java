package com.floattime.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 超级岛管理器 (小米 HyperOS 焦点通知)
 *
 * 参考 HyperNotification (https://github.com/xzakota/HyperNotification) 的实现方案，
 * 通过 Notification extras 注入小米焦点通知数据，实现超级岛显示。
 *
 * 工作原理:
 * - 小米 HyperOS 系统会检查通知 extras 中的 "miui.focus.param" 字段
 * - 如果存在有效 JSON，系统将通知提升为焦点通知 (超级岛)
 * - 数据通过 miui.focus.pics / miui.focus.actions 传递图片和操作
 *
 * 注意:
 * - 需要 HyperOS 系统支持
 * - 焦点通知有白名单限制，可能需要 XP 模块解除
 * - 详情参见: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131
 */
public class SuperIslandManager {

    private static final String TAG = "SuperIslandManager";

    // =============================================
    //  小米焦点通知 Bundle Keys
    // =============================================
    private static final String FOCUS_PARAM         = "miui.focus.param";
    private static final String FOCUS_PARAM_CUSTOM  = "miui.focus.param.custom";
    private static final String FOCUS_PICS          = "miui.focus.pics";
    private static final String FOCUS_ACTIONS       = "miui.focus.actions";

    // =============================================
    //  通知渠道
    // =============================================
    private static final String CHANNEL_ID = "float_time_super_island";
    private static final String CHANNEL_NAME = "悬浮时间超级岛";
    private static final int NOTIFICATION_ID = 20240322;

    // =============================================
    //  焦点通知类型常量 (参考 HyperNotification)
    // =============================================
    private static final int INFO_TYPE_TEXT = 1;     // 纯文本
    private static final int INFO_TYPE_IMAGE = 2;    // 图文

    private final Context mContext;
    private final NotificationManager mNotifMgr;
    private final boolean mIsHyperOS;

    private boolean mIsEnabled = false;

    public SuperIslandManager(Context context) {
        mContext = context.getApplicationContext();
        mNotifMgr = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mIsHyperOS = detectHyperOS();
        createNotificationChannel();

        Log.d(TAG, "SuperIslandManager initialized | SDK: " + Build.VERSION.SDK_INT
                + " | Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " | HyperOS: " + mIsHyperOS);
    }

    // =============================================
    //  检测 HyperOS
    // =============================================

    /**
     * 检测是否为小米 HyperOS / MIUI 系统
     */
    private boolean detectHyperOS() {
        try {
            // HyperOS 特征: ro.mi.os.version.incremental 存在
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            String prop = (String) clazz.getMethod("get", String.class)
                    .invoke(null, "ro.mi.os.version.incremental");
            if (prop != null && !prop.isEmpty()) {
                Log.d(TAG, "HyperOS version: " + prop);
                return true;
            }
        } catch (Exception ignored) {}

        // 备用检测: 厂商为 Xiaomi / Redmi / POCO
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.contains("xiaomi") || manufacturer.contains("redmi");
    }

    // =============================================
    //  通知渠道
    // =============================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("悬浮时间的超级岛显示");
            channel.setShowBadge(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            mNotifMgr.createNotificationChannel(channel);
        }
    }

    // =============================================
    //  公共 API
    // =============================================

    /**
     * 是否为 HyperOS 系统
     */
    public boolean isHyperOS() {
        return mIsHyperOS;
    }

    /**
     * 是否支持超级岛 (需要 HyperOS + Android 12+)
     */
    public boolean isSupported() {
        return mIsHyperOS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    public void enable(OnResultListener listener) {
        mIsEnabled = true;
        if (listener != null) {
            listener.onSuccess("超级岛已启用");
        }
    }

    /**
     * 将小米焦点通知 extras 注入到通知中
     *
     * 参考 HyperNotification FocusNotification.buildV2() 的实现:
     * ```kotlin
     * val extras = FocusNotification.buildV2 {
     *     enableFloat = true
     *     ticker = "Ticker"
     *     baseInfo { type = 1; title = "Title"; content = "Content" }
     *     hintInfo { type = 1; title = "Hint"; content = "HintContent" }
     * }
     * notificationManager.notify(id, builder.addExtras(extras).build())
     * ```
     *
     * @param notification 要注入 extras 的通知对象
     * @param timeStr      时间字符串 (HH:mm:ss)
     * @param millisStr    毫秒字符串 (.xxx)
     * @param source       来源显示名称
     */
    public void applyFocusExtras(Notification notification,
                                 String timeStr, String millisStr,
                                 String source) {
        if (!isSupported()) return;

        try {
            Bundle focusExtras = buildFocusExtrasV2(timeStr, millisStr, source);
            notification.extras.putAll(focusExtras);
            Log.d(TAG, "Focus extras applied: " + timeStr + millisStr);
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
     *
     * 对应 HyperNotification 的 FocusTemplate:
     * - ticker: 状态栏摘要文本
     * - enableFloat: 是否启用浮窗 (超级岛)
     * - baseInfo: 基础文本信息 (类型/标题/内容)
     * - hintInfo: 提示按钮信息
     * - progressInfo: 进度信息 (可用于秒表场景)
     *
     * @return 包含 miui.focus.param 的 Bundle
     */
    private Bundle buildFocusExtrasV2(String timeStr, String millisStr, String source) {
        Bundle extras = new Bundle();

        try {
            // 构建 V2 模板 JSON (对应 HyperNotification 的 FocusTemplate)
            JSONObject template = new JSONObject();

            // ticker — 状态栏显示的摘要
            template.put("ticker", timeStr + millisStr);

            // enableFloat — 启用浮窗/超级岛
            template.put("enableFloat", true);

            // baseInfo — 基础文本组件
            // 对应: template.baseInfo { type = 1; title = "..."; content = "..." }
            JSONObject baseInfo = new JSONObject();
            baseInfo.put("type", INFO_TYPE_TEXT);
            baseInfo.put("title", "校准时间 " + timeStr);
            baseInfo.put("content", source + " | " + millisStr);
            template.put("baseInfo", baseInfo);

            // hintInfo — 提示/按钮组件
            // 对应: template.hintInfo { type = 1; title = "..."; content = "..." }
            JSONObject hintInfo = new JSONObject();
            hintInfo.put("type", INFO_TYPE_TEXT);
            hintInfo.put("title", "查看详情");
            hintInfo.put("content", "点击打开悬浮时间");
            template.put("hintInfo", hintInfo);

            // 将完整 JSON 放入 extras
            // key = "miui.focus.param" (小米系统读取此 key)
            extras.putString(FOCUS_PARAM, template.toString());

            Log.d(TAG, "Focus V2 JSON: " + template.toString(2));

        } catch (Exception e) {
            Log.e(TAG, "buildFocusExtrasV2 failed: " + e.getMessage());
        }

        return extras;
    }

    /**
     * 构建通知 (独立显示用)
     */
    private Notification buildNotification(String time, String millis, String source) {
        Intent mainIntent = new Intent(mContext, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            mContext, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(time + millis)
            .setContentText(source)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        Notification notification = builder.build();

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
