package com.floattime.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 小米焦点通知参数构建器 (V3 格式)
 *
 * 参考 Capsulyric 的 FocusNotification.buildV3 DSL 实现，
 * 用纯 Java 构建与 HyperNotification V3 等价的 JSON 结构。
 *
 * 生成的 JSON 写入 notification.extras 的 "miui.focus.param" key，
 * HyperOS 系统会读取此 key 并在超级岛上展示内容。
 *
 * 模板类型：
 * - 带 progressInfo → Template 21 (IM + Progress 2)
 * - 带 actions → Template 7 (Media Controls)
 */
public class FocusParamBuilder {

    private static final String TAG = "FocusParamBuilder";

    // 小米焦点通知 Bundle Keys
    public static final String KEY_FOCUS_PARAM = "miui.focus.param";
    public static final String KEY_FOCUS_PICS = "miui.focus.pics";
    public static final String KEY_FOCUS_ACTIONS = "miui.focus.actions";

    // 通知渠道
    public static final String CHANNEL_ID = "float_time_live_updates";

    private final Context mContext;

    // V3 参数
    private String mBusiness = "float_time";
    private boolean mEnableFloat = false;
    private boolean mUpdatable = true;
    private boolean mIslandFirstFloat = false;

    // AOD (息屏显示)
    private String mAodTitle = "";

    // ChatInfo (通知栏聊天式信息)
    private String mChatTitle = "";
    private String mChatContent = "";
    private String mChatAppIconPkg = "";

    // Island (超级岛区域)
    private String mIslandTitle = "";
    private String mIslandTextTitle = "";
    private boolean mShowHighlightColor = false;
    private String mHighlightColor = "#757575";

    // Small Island (胶囊形态)
    private int mProgress = 0;
    private String mProgressColor = "#757575";
    private String mProgressColorUnreach = "#333333";

    // Share Data (拖拽分享)
    private boolean mShareEnabled = true;
    private String mShareTitle = "";
    private String mShareContent = "";

    // Ticker (状态栏摘要)
    private String mTicker = "";

    public FocusParamBuilder(Context context) {
        mContext = context.getApplicationContext();
    }

    // =============================================
    //  链式设置方法
    // =============================================

    public FocusParamBuilder setBusiness(String business) {
        mBusiness = business;
        return this;
    }

    public FocusParamBuilder setEnableFloat(boolean enable) {
        mEnableFloat = enable;
        return this;
    }

    public FocusParamBuilder setUpdatable(boolean updatable) {
        mUpdatable = updatable;
        return this;
    }

    public FocusParamBuilder setIslandFirstFloat(boolean first) {
        mIslandFirstFloat = first;
        return this;
    }

    public FocusParamBuilder setAodTitle(String title) {
        mAodTitle = title;
        return this;
    }

    public FocusParamBuilder setTicker(String ticker) {
        mTicker = ticker;
        return this;
    }

    // ChatInfo
    public FocusParamBuilder setChatTitle(String title) {
        mChatTitle = title;
        return this;
    }

    public FocusParamBuilder setChatContent(String content) {
        mChatContent = content;
        return this;
    }

    public FocusParamBuilder setChatAppIconPkg(String pkg) {
        mChatAppIconPkg = pkg;
        return this;
    }

    // Island
    public FocusParamBuilder setIslandTitle(String title) {
        mIslandTitle = title;
        return this;
    }

    public FocusParamBuilder setIslandTextTitle(String title) {
        mIslandTextTitle = title;
        return this;
    }

    public FocusParamBuilder setShowHighlightColor(boolean show) {
        mShowHighlightColor = show;
        return this;
    }

    public FocusParamBuilder setHighlightColor(String color) {
        mHighlightColor = color;
        return this;
    }

    // Progress
    public FocusParamBuilder setProgress(int progress) {
        mProgress = progress;
        return this;
    }

    public FocusParamBuilder setProgressColor(String color) {
        mProgressColor = color;
        return this;
    }

    public FocusParamBuilder setProgressColorUnreach(String color) {
        mProgressColorUnreach = color;
        return this;
    }

    // Share
    public FocusParamBuilder setShareEnabled(boolean enabled) {
        mShareEnabled = enabled;
        return this;
    }

    public FocusParamBuilder setShareTitle(String title) {
        mShareTitle = title;
        return this;
    }

    public FocusParamBuilder setShareContent(String content) {
        mShareContent = content;
        return this;
    }

    // =============================================
    //  构建方法
    // =============================================

    /**
     * 构建 miui.focus.param JSON 字符串
     */
    public String buildParamJson() {
        try {
            JSONObject root = new JSONObject();

            // 顶层属性
            root.put("business", mBusiness);
            root.put("enableFloat", mEnableFloat);
            root.put("updatable", mUpdatable);
            root.put("islandFirstFloat", mIslandFirstFloat);
            root.put("aodTitle", mAodTitle);
            root.put("ticker", mTicker);

            // chatInfo — 通知栏展示
            JSONObject chatInfo = new JSONObject();
            chatInfo.put("title", mChatTitle);
            chatInfo.put("content", mChatContent);
            if (!mChatAppIconPkg.isEmpty()) {
                chatInfo.put("appIconPkg", mChatAppIconPkg);
            }
            root.put("chatInfo", chatInfo);

            // island — 超级岛
            JSONObject island = new JSONObject();
            island.put("islandProperty", 1);
            if (mShowHighlightColor) {
                island.put("highlightColor", mHighlightColor);
            }

            // bigIslandArea — 展开态
            JSONObject bigIslandArea = new JSONObject();
            JSONObject imageTextInfoLeft = new JSONObject();
            imageTextInfoLeft.put("type", 1);

            JSONObject textInfoLeft = new JSONObject();
            textInfoLeft.put("title", mIslandTitle);
            textInfoLeft.put("showHighlightColor", mShowHighlightColor);
            imageTextInfoLeft.put("textInfo", textInfoLeft);
            bigIslandArea.put("imageTextInfoLeft", imageTextInfoLeft);

            // textInfo — 右侧滚动文本
            JSONObject textInfo = new JSONObject();
            textInfo.put("title", mIslandTextTitle);
            textInfo.put("showHighlightColor", mShowHighlightColor);
            textInfo.put("narrowFont", false);
            bigIslandArea.put("textInfo", textInfo);

            island.put("bigIslandArea", bigIslandArea);

            // smallIslandArea — 胶囊形态
            JSONObject smallIslandArea = new JSONObject();
            JSONObject combinePicInfo = new JSONObject();
            JSONObject progressInfo = new JSONObject();
            progressInfo.put("progress", mProgress);
            progressInfo.put("colorReach", mProgressColor);
            progressInfo.put("colorUnReach", mProgressColorUnreach);
            combinePicInfo.put("progressInfo", progressInfo);
            smallIslandArea.put("combinePicInfo", combinePicInfo);
            island.put("smallIslandArea", smallIslandArea);

            // shareData — 拖拽分享
            if (mShareEnabled) {
                JSONObject shareData = new JSONObject();
                shareData.put("title", mShareTitle);
                shareData.put("content", mShareContent);
                island.put("shareData", shareData);
            }

            root.put("island", island);

            return root.toString();

        } catch (Exception e) {
            Log.e(TAG, "buildParamJson failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 构建完整的焦点通知 Bundle
     * 包含 miui.focus.param JSON
     */
    public Bundle buildExtras() {
        Bundle extras = new Bundle();
        String json = buildParamJson();
        if (json != null) {
            extras.putString(KEY_FOCUS_PARAM, json);
        }
        return extras;
    }

    /**
     * 构建带有焦点通知的完整 Notification
     *
     * @param timeStr    时间字符串 (HH:mm:ss)
     * @param millisStr  毫秒字符串 (.xxx)
     * @param source     时间源名称
     * @param isNight    是否夜间模式
     */
    public Notification buildNotification(String timeStr, String millisStr,
                                          String source, boolean isNight) {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                mContext, 0,
                new Intent(mContext, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

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

        // 注入焦点通知 extras
        Bundle focusExtras = buildExtras();
        notification.extras.putAll(focusExtras);

        return notification;
    }

    private static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
}
