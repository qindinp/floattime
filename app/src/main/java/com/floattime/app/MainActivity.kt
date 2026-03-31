package com.floattime.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.floattime.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // ViewBinding — ECC android-clean-architecture: no findViewById
    private lateinit var binding: ActivityMainBinding

    // SettingsRepository — ECC clean-architecture: encapsulate SharedPreferences
    private lateinit var settings: SettingsRepository

    private val handler = Handler(Looper.getMainLooper())
    private var currentTimeRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository(this)

        setupTimeDisplay()
        setupServiceControls()
        setupSourceSelector()
        setupSuperIsland()
        setupThemeSelector()
        setupFloatWindow()
        applyTheme()
        updateServiceStatus()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        startClockUpdates()
        updateServiceStatus()
    }

    override fun onPause() {
        super.onPause()
        stopClockUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopClockUpdates()
    }

    // --- Clock Display ---

    private fun setupTimeDisplay() {
        updateMainTime()
    }

    private fun startClockUpdates() {
        stopClockUpdates()
        val runnable = object : Runnable {
            override fun run() {
                updateMainTime()
                currentTimeRunnable = this
                handler.postDelayed(this, 100)
            }
        }
        currentTimeRunnable = runnable
        handler.post(runnable)
    }

    private fun stopClockUpdates() {
        currentTimeRunnable?.let { handler.removeCallbacks(it) }
        currentTimeRunnable = null
    }

    private fun updateMainTime() {
        try {
            val now = System.currentTimeMillis()
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            binding.currentTimeText.text = sdf.format(Date(now))
            binding.timezoneText.text = "GMT+${(java.util.TimeZone.getDefault().rawOffset / 3600000)}"
        } catch (_: Exception) {}
    }

    // --- Service Controls ---

    private fun setupServiceControls() {
        binding.startBtn.setOnClickListener {
            checkOverlayPermission()
        }
        binding.stopBtn.setOnClickListener {
            stopFloatingService()
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1001)
            return
        }
        startFloatingService()
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatTimeService::class.java).apply {
            putExtra("time_source", settings.timeSource)
            putExtra("super_island", settings.superIslandEnabled)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        settings.isServiceRunning = true
        updateServiceStatus()
        Toast.makeText(this, "悬浮时间已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatTimeService::class.java)
        stopService(intent)
        settings.isServiceRunning = false
        updateServiceStatus()
        Toast.makeText(this, "悬浮时间已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateServiceStatus() {
        val running = settings.isServiceRunning
        binding.statusText.text = if (running) "服务运行中" else "服务未启动"
        binding.startBtn.isEnabled = !running
        binding.stopBtn.isEnabled = running
    }

    // --- Time Source Selector ---

    private fun setupSourceSelector() {
        when (settings.timeSource) {
            "taobao" -> binding.sourceTaobao.isChecked = true
            "meituan" -> binding.sourceMeituan.isChecked = true
            else -> binding.sourceLocal.isChecked = true
        }
        updateSourceText()

        binding.sourceGroup.setOnCheckedChangeListener { _, checkedId ->
            val source = when (checkedId) {
                R.id.sourceTaobao -> "taobao"
                R.id.sourceMeituan -> "meituan"
                else -> "local"
            }
            settings.timeSource = source
            updateSourceText()
            if (settings.isServiceRunning) {
                stopFloatingService()
                handler.postDelayed({ startFloatingService() }, 500)
            }
        }
    }

    private fun updateSourceText() {
        val name = when (settings.timeSource) {
            "taobao" -> "淘宝服务器"
            "meituan" -> "美团服务器"
            else -> "本地时钟"
        }
        binding.sourceText.text = "来源: $name"
    }

    // --- Super Island ---

    private fun setupSuperIsland() {
        binding.superIslandSwitch.isChecked = settings.superIslandEnabled
        binding.superIslandHint.text = "开启后需要 Shizuku 授权"

        binding.superIslandSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.superIslandEnabled = isChecked
            if (settings.isServiceRunning) {
                stopFloatingService()
                handler.postDelayed({ startFloatingService() }, 500)
            }
        }
    }

    // --- Float Window ---

    private fun setupFloatWindow() {
        binding.floatWindowSwitch.isChecked = settings.floatWindowEnabled
        binding.floatWindowSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.floatWindowEnabled = isChecked
        }
    }

    // --- Theme ---

    private fun setupThemeSelector() {
        when (settings.theme) {
            "light" -> binding.themeLight.isChecked = true
            "dark" -> binding.themeDark.isChecked = true
            else -> binding.themeAuto.isChecked = true
        }

        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.themeLight -> "light"
                R.id.themeDark -> "dark"
                else -> "auto"
            }
            settings.theme = theme
            applyTheme()
        }
    }

    private fun applyTheme() {
        val isNight = when (settings.theme) {
            "light" -> false
            "dark" -> true
            else -> {
                val currentNight = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                currentNight == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }

        val bgColor = if (isNight) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        val textColor = if (isNight) 0xFFFFFFFF.toInt() else 0xFF1A1A2E.toInt()
        val accentColor = if (isNight) 0xFF03DAC6.toInt() else 0xFFBB86FC.toInt()
        val hintColor = if (isNight) 0xFF888888.toInt() else 0xFF666666.toInt()

        binding.root.setBackgroundColor(bgColor)

        binding.statusText.setTextColor(textColor)
        binding.timezoneText.setTextColor(textColor)
        binding.currentTimeText.setTextColor(textColor)
        binding.versionText.setTextColor(hintColor)
        binding.sourceText.setTextColor(hintColor)
        binding.superIslandHint.setTextColor(hintColor)

        // Update button styles
        val startBtnBg = if (isNight) 0xFF03DAC6.toInt() else 0xFF6200EE.toInt()
        val stopBtnBg = if (isNight) 0xFF757575.toInt() else 0xFF757575.toInt()
        binding.startBtn.setBackgroundColor(startBtnBg)
        binding.stopBtn.setBackgroundColor(stopBtnBg)
    }

    // --- Notification Permission (Android 13+) ---

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1002
                )
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        fun isRunning(): Boolean = false
    }
}
