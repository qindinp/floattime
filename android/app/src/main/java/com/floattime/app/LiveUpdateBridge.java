package com.floattime.app;

import android.webkit.JavascriptInterface;

/**
 * Live Update JavaScript 接口
 * 供 Web 端调用 Android 16 Live Updates
 */
public class LiveUpdateBridge {
    
    private final MainActivity mActivity;
    
    public LiveUpdateBridge(MainActivity activity) {
        mActivity = activity;
    }
    
    /**
     * 检查是否支持 Live Updates
     */
    @JavascriptInterface
    public boolean isLiveUpdateSupported() {
        return mActivity.isLiveUpdateSupported();
    }
    
    /**
     * 显示时间同步中
     */
    @JavascriptInterface
    public void showTimeSyncing(String source) {
        mActivity.runOnUiThread(() -> {
            mActivity.showTimeSyncing(source);
        });
    }
    
    /**
     * 显示时间同步成功
     */
    @JavascriptInterface
    public void showTimeSyncSuccess(String source, long offsetMs) {
        mActivity.runOnUiThread(() -> {
            mActivity.showTimeSyncSuccess(source, offsetMs);
        });
    }
    
    /**
     * 显示时间同步失败
     */
    @JavascriptInterface
    public void showTimeSyncFailed(String source) {
        mActivity.runOnUiThread(() -> {
            mActivity.showTimeSyncFailed(source);
        });
    }
    
    /**
     * 启动秒表 Live Update
     */
    @JavascriptInterface
    public void startStopwatch() {
        mActivity.runOnUiThread(() -> {
            mActivity.startStopwatchLiveUpdate();
        });
    }
    
    /**
     * 暂停秒表
     */
    @JavascriptInterface
    public void pauseStopwatch() {
        mActivity.runOnUiThread(() -> {
            mActivity.pauseStopwatchLiveUpdate();
        });
    }
    
    /**
     * 停止秒表
     */
    @JavascriptInterface
    public void stopStopwatch() {
        mActivity.runOnUiThread(() -> {
            mActivity.stopStopwatchLiveUpdate();
        });
    }
}
