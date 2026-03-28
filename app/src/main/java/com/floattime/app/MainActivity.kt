package com.floattime.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * FloatTime 主界面
 * 底部抽屉双 Tab: 状态 | 服务
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "FloatTimePrefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_PERMISSIONS_REQUESTED = "permissions_requested"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tabStatus: TextView
    private lateinit var tabService: TextView
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<*>

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission granted")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setContentView(R.layout.activity_main)

        setupBottomSheet()
        requestInitialPermissions()

        // Default to time (status) tab
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, TimeFragment())
                .commit()
            showTab(0)
        }
    }

    private fun setupBottomSheet() {
        val bottomSheet = findViewById<android.view.View>(R.id.bottomSheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        tabStatus = findViewById(R.id.tabStatus)
        tabService = findViewById(R.id.tabService)

        tabStatus.setOnClickListener { showTab(0) }
        tabService.setOnClickListener { showTab(1) }
    }

    private fun showTab(index: Int) {
        val tabContent = findViewById<android.widget.FrameLayout>(R.id.tabContent)

        when (index) {
            0 -> {
                // 状态 tab
                tabStatus.setTextColor(0xFFFF6B35.toInt())
                tabStatus.typeface = android.graphics.Typeface.DEFAULT_BOLD
                tabService.setTextColor(0xFF999999.toInt())
                tabService.typeface = android.graphics.Typeface.DEFAULT

                // Show time info in bottom sheet
                val fragment = TimeSheetFragment()
                supportFragmentManager.beginTransaction()
                    .replace(R.id.tabContent, fragment)
                    .commit()
            }
            1 -> {
                // 服务 tab
                tabService.setTextColor(0xFFFF6B35.toInt())
                tabService.typeface = android.graphics.Typeface.DEFAULT_BOLD
                tabStatus.setTextColor(0xFF999999.toInt())
                tabStatus.typeface = android.graphics.Typeface.DEFAULT

                val fragment = ServiceFragment()
                supportFragmentManager.beginTransaction()
                    .replace(R.id.tabContent, fragment)
                    .commit()
            }
        }
    }

    private fun requestInitialPermissions() {
        if (prefs.getBoolean(KEY_PERMISSIONS_REQUESTED, false)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.FOREGROUND_SERVICE), 1003
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } catch (_: Exception) {}
        }

        prefs.edit().putBoolean(KEY_PERMISSIONS_REQUESTED, true).apply()
    }
}
