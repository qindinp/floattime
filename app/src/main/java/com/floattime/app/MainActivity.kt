package com.floattime.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 主界面 - 负责权限申请、服务启动、主题设置
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "FloatTimePrefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_PERMISSIONS_REQUESTED = "permissions_requested"
    }

    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusText: TextView
    private lateinit var timezoneText: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var versionText: TextView
    private lateinit var themeGroup: RadioGroup
    private lateinit var themeAuto: RadioButton
    private lateinit var themeLight: RadioButton
    private lateinit var themeDark: RadioButton
    private lateinit var floatWindowSwitch: Switch

    private lateinit var prefs: SharedPreferences
    private lateinit var liveUpdateManager: LiveUpdateManager
    private lateinit var handler: Handler
    private var timeRunnable: Runnable? = null

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                checkNotificationPermissionAndStart()
            } else {
                showStatus("❌ 需要悬浮窗权限")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用暗夜模式支持 - 跟随系统设置
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        liveUpdateManager = LiveUpdateManager(this)

        setContentView(R.layout.activity_main)

        // 初始化 View
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        statusText = findViewById(R.id.statusText)
        timezoneText = findViewById(R.id.timezoneText)
        currentTimeText = findViewById(R.id.currentTimeText)
        versionText = findViewById(R.id.versionText)
        themeGroup = findViewById(R.id.themeGroup)
        themeAuto = findViewById(R.id.themeAuto)
        themeLight = findViewById(R.id.themeLight)
        themeDark = findViewById(R.id.themeDark)
        floatWindowSwitch = findViewById(R.id.floatWindowSwitch)

        startBtn.setOnClickListener { checkOverlayPermission() }
        stopBtn.setOnClickListener { stopFloatingService() }

        // 悬浮窗开关
        floatWindowSwitch.isChecked = prefs.getBoolean("float_window_enabled", true)
        floatWindowSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("float_window_enabled", isChecked).apply()
            if (FloatTimeService.isRunning()) {
                stopFloatingService()
                handler.postDelayed({ checkOverlayPermission() }, 500)
            }
        }

        versionText.text = "FloatTime v1.3.0"

        setupThemeSelector()
        applyThemeMode(prefs.getInt(KEY_THEME_MODE, 0))

        displayTimezone()
        startMainClock()
        updateStatus()

        // 首次启动请求权限
        if (!prefs.getBoolean(KEY_PERMISSIONS_REQUESTED, false)) {
            requestInitialPermissions()
            prefs.edit().putBoolean(KEY_PERMISSIONS_REQUESTED, true).apply()
        }

        Log.d(TAG, "MainActivity onCreate - Dark mode enabled, permissions checked")
    }

    private fun requestInitialPermissions() {
        Log.d(TAG, "Requesting initial permissions")

        // Android 13+ 请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }

        // 悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
        }
    }

    private fun setupThemeSelector() {
        when (prefs.getInt(KEY_THEME_MODE, 0)) {
            0 -> themeAuto.isChecked = true
            1 -> themeLight.isChecked = true
            2 -> themeDark.isChecked = true
        }

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLight -> 1
                R.id.themeDark -> 2
                else -> 0
            }
            prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
            applyThemeMode(mode)

            if (FloatTimeService.isRunning()) {
                updateServiceTheme(mode)
            }
            Log.d(TAG, "Theme changed to: $mode")
        }
    }

    private fun applyThemeMode(mode: Int) {
        val isNight = FloatTimeService.calcNightMode(mode)

        val bgColor = if (isNight) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        val textColor = if (isNight) 0xFFFFFFFF.toInt() else 0xFF1A1A2E.toInt()
        val accentColor = if (isNight) 0xFF03DAC6.toInt() else 0xFFBB86FC.toInt()

        findViewById<View>(android.R.id.content).setBackgroundColor(bgColor)

        statusText.setTextColor(textColor)
        timezoneText.setTextColor(textColor)
        currentTimeText.setTextColor(textColor)
        versionText.setTextColor(textColor)

        updateButtonStyle(startBtn, isNight, accentColor)
        updateButtonStyle(stopBtn, isNight, accentColor)

        floatWindowSwitch.setTextColor(textColor)
    }

    private fun updateButtonStyle(btn: Button?, isNight: Boolean, accentColor: Int) {
        btn ?: return

        val bgColor = if (isNight) 0xFF2A2A2A.toInt() else 0xFFE0E0E0.toInt()
        val textColor = if (isNight) accentColor else 0xFF1A1A2E.toInt()

        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(bgColor)
            setStroke(dpToPx(2), textColor)
        }
        btn.setTextColor(textColor)
    }

    private fun dpToPx(dp: Float): Int =
        Math.round(dp * resources.displayMetrics.density)

    private fun updateServiceTheme(mode: Int) {
        val intent = Intent(this, FloatTimeService::class.java).apply {
            action = "CHANGE_THEME"
            putExtra("mode", mode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        timeRunnable?.let { handler.removeCallbacks(it) }
        if (::liveUpdateManager.isInitialized) {
            liveUpdateManager.clearAll()
        }
    }

    // ==================== Live Update 公共方法 ====================

    fun isSupported(): Boolean = ::liveUpdateManager.isInitialized && liveUpdateManager.isSupported()
    fun showTimeSyncing(source: String) = liveUpdateManager.showTimeSyncing(source)
    fun showTimeSyncSuccess(source: String, offsetMs: Long) = liveUpdateManager.showTimeSyncSuccess(source, offsetMs)
    fun showTimeSyncFailed(source: String) = liveUpdateManager.showTimeSyncFailed(source)
    fun startStopwatchLiveUpdate() = liveUpdateManager.startStopwatch()
    fun pauseStopwatchLiveUpdate() = liveUpdateManager.pauseStopwatch()
    fun stopStopwatchLiveUpdate() = liveUpdateManager.stopStopwatch()

    private fun displayTimezone() {
        val tz = TimeZone.getDefault()
        val offset = tz.rawOffset / (1000 * 60 * 60)
        val offsetStr = if (offset >= 0) "GMT+$offset" else "GMT$offset"
        timezoneText.text = "${tz.id} ($offsetStr)"
    }

    private fun startMainClock() {
        handler = Handler(Looper.getMainLooper())
        timeRunnable = object : Runnable {
            override fun run() {
                updateMainTime()
                handler.postDelayed(this, 500)
            }
        }.also { handler.post(it) }
    }

    private fun updateMainTime() {
        try {
            val now = System.currentTimeMillis()
            val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            currentTimeText.text = timeFmt.format(Date(now))
        } catch (_: Exception) {}
    }

    private fun updateStatus() {
        val running = FloatTimeService.isRunning()
        runOnUiThread {
            if (running) {
                statusText.text = "✅ 悬浮时间服务运行中"
                startBtn.isEnabled = false
                stopBtn.isEnabled = true
            } else {
                statusText.text = "⭕ 服务未启动"
                startBtn.isEnabled = true
                stopBtn.isEnabled = false
            }
        }
    }

    private fun showStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                checkNotificationPermissionAndStart()
            } else {
                showOverlayPermissionDialog()
            }
        } else {
            startFloatingService()
        }
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("悬浮窗选项")
            .setMessage("选择是否启用悬浮窗显示")
            .setPositiveButton("启用悬浮窗") { _, _ ->
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    prefs.edit().putBoolean("float_window_enabled", true).apply()
                    overlayPermissionLauncher.launch(intent)
                } catch (_: Exception) {
                    openAppSettings()
                }
            }
            .setNegativeButton("仅后台运行") { _, _ ->
                prefs.edit().putBoolean("float_window_enabled", false).apply()
                checkNotificationPermissionAndStart()
            }
            .setNeutralButton("取消", null)
            .setCancelable(false)
            .show()
    }

    private fun checkNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002
                )
            } else {
                startFloatingService()
            }
        } else {
            startFloatingService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 || requestCode == 1001) {
            startFloatingService()
        }
    }

    private fun startFloatingService() {
        try {
            val intent = Intent(this, FloatTimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            showStatus("✅ 悬浮时间服务已启动")
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
            Toast.makeText(this, "悬浮时间已启动", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Service started")
        } catch (e: Exception) {
            showStatus("❌ 启动失败: ${e.message}")
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Start failed: ${e.message}")
        }
    }

    private fun stopFloatingService() {
        try {
            stopService(Intent(this, FloatTimeService::class.java))

            showStatus("⭕ 服务已停止")
            startBtn.isEnabled = true
            stopBtn.isEnabled = false
            Toast.makeText(this, "悬浮时间已停止", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Service stopped")
        } catch (e: Exception) {
            showStatus("❌ 停止失败: ${e.message}")
            Log.e(TAG, "Stop failed: ${e.message}")
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            ))
        } catch (_: Exception) {}
    }
}
