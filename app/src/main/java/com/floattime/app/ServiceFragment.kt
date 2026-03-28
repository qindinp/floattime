package com.floattime.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

/**
 * 服务 Tab 内容
 * 显示校准时间信息 + 开关设置 + 启动/停止按钮
 */
class ServiceFragment : Fragment() {

    private lateinit var currentCalibratedTime: TextView
    private lateinit var calibrationSubtitle: TextView
    private lateinit var switchFloatingWindow: SwitchCompat
    private lateinit var switchMuteReminder: SwitchCompat
    private lateinit var btnStart: TextView
    private lateinit var btnLogout: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.tab_service, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentCalibratedTime = view.findViewById(R.id.currentCalibratedTime)
        calibrationSubtitle = view.findViewById(R.id.calibrationSubtitle)
        switchFloatingWindow = view.findViewById(R.id.switchFloatingWindow)
        switchMuteReminder = view.findViewById(R.id.switchMuteReminder)
        btnStart = view.findViewById(R.id.btnStart)
        btnLogout = view.findViewById(R.id.btnLogout)

        // Load preferences
        val prefs = requireContext().getSharedPreferences("FloatTimePrefs", Context.MODE_PRIVATE)
        switchFloatingWindow.isChecked = prefs.getBoolean("floating_window", true)
        switchMuteReminder.isChecked = prefs.getBoolean("mute_reminder", true)

        switchFloatingWindow.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("floating_window", isChecked).apply()
            // Toggle floating service
            val ctx = requireContext()
            if (isChecked) {
                val intent = Intent(ctx, FloatTimeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } else {
                ctx.stopService(Intent(ctx, FloatTimeService::class.java))
            }
        }

        switchMuteReminder.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mute_reminder", isChecked).apply()
        }

        // Start/Stop button
        btnStart.setOnClickListener {
            val ctx = requireContext()
            val isRunning = FloatTimeService.isRunning()
            if (isRunning) {
                ctx.stopService(Intent(ctx, FloatTimeService::class.java))
                Toast.makeText(ctx, "服务已停止", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(ctx, FloatTimeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
                prefs.edit().putBoolean("service_was_started", true).apply()
                Toast.makeText(ctx, "服务已启动", Toast.LENGTH_SHORT).show()
            }
            updateButtonState()
        }

        btnLogout.setOnClickListener {
            Toast.makeText(requireContext(), "已退出登录", Toast.LENGTH_SHORT).show()
        }

        startCalibrationUpdate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updateRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun startCalibrationUpdate() {
        updateRunnable = object : Runnable {
            override fun run() {
                updateCalibrationDisplay()
                updateButtonState()
                handler.postDelayed(this, 1000)
            }
        }.also { handler.post(it) }
    }

    private fun updateCalibrationDisplay() {
        if (!isAdded) return

        val prefs = requireContext().getSharedPreferences("FloatTimePrefs", Context.MODE_PRIVATE)
        val offsetMs = prefs.getLong("offset_ms", 0)
        val timeSource = prefs.getString("time_source", "taobao") ?: "taobao"

        val now = System.currentTimeMillis() + offsetMs
        val millis = now % 1000
        val seconds = (now / 1000) % 60
        val minutes = (now / 60000) % 60
        val hours = (now / 3600000) % 24
        val timeStr = String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)

        currentCalibratedTime.text = timeStr

        val sourceName = when (timeSource) {
            "taobao" -> "淘宝"
            "meituan" -> "美团"
            else -> "本地"
        }
        calibrationSubtitle.text = "校准时间 ($sourceName) | 偏移量: ${offsetMs}ms"
    }

    private fun updateButtonState() {
        if (!isAdded) return
        val isRunning = FloatTimeService.isRunning()
        btnStart.text = if (isRunning) "关" else "开"
        btnStart.alpha = if (isRunning) 0.7f else 1.0f
    }
}
