package com.floattime.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启接收器
 * 仅在用户之前启动过服务时才自动恢复
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return

        // 用户从未手动启动过服务，不自启
        val prefs = context.getSharedPreferences("FloatTimePrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("service_was_started", false)) return

        val serviceIntent = Intent(context, FloatTimeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
