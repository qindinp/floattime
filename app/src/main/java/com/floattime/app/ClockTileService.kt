package com.floattime.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * ClockTileService - 快捷设置磁贴（Quick Settings Tile）
 *
 * 用户可以在系统通知栏的快捷设置中添加"悬浮时间"磁贴，
 * 点击即可快速启动/停止悬浮时间服务。
 *
 * Android 12+ (API 31) 起支持 TileService
 *
 * 与 Live Island 的关系:
 * - TileService 是用户主动添加的入口
 * - 点击磁贴 → 启动 FloatTimeService → Live Island 自动激活
 * - 无 Live Island 的设备 → 仅通知栏显示
 * - 有 Live Island 的设备 → 通知栏 + Live Island
 *
 * @see <a href="https://developer.android.com/reference/android/service/quicksettings/TileService">TileService</a>
 */
@RequiresApi(Build.VERSION_CODES.N)
class ClockTileService : TileService() {

    companion object {
        private const val TAG = "ClockTileService"
        private const val PREFS_NAME = "FloatTimePrefs"
        private const val KEY_SERVICE_ACTIVE = "tile_service_active"
    }

    private lateinit var prefs: SharedPreferences

    override fun onStartListening() {
        super.onStartListening()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 同步磁贴状态
        val isRunning = FloatTimeService.isRunning()
        prefs.edit().putBoolean(KEY_SERVICE_ACTIVE, isRunning).apply()
        qsTile?.apply { state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }

        Log.d(TAG, "onStartListening | isRunning=$isRunning")
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "onStopListening")
    }

    override fun onClick() {
        super.onClick()

        val isRunning = FloatTimeService.isRunning()

        if (isRunning) {
            // 停止服务
            stopService()
            updateTile(Tile.STATE_INACTIVE)
            Log.d(TAG, "Tile clicked: stopping service")
        } else {
            // 启动服务
            startService()
            updateTile(Tile.STATE_ACTIVE)
            Log.d(TAG, "Tile clicked: starting service")
        }
    }

    private fun startService() {
        try {
            val intent = Intent(this, FloatTimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
        } catch (e: Exception) {
            Log.e(TAG, "startService failed: ${e.message}")
            qsTile?.apply { state = Tile.STATE_UNAVAILABLE; updateTile() }
        }
    }

    private fun stopService() {
        try {
            stopService(Intent(this, FloatTimeService::class.java))
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
        } catch (e: Exception) {
            Log.e(TAG, "stopService failed: ${e.message}")
        }
    }

    private fun updateTile(state: Int) {
        qsTile?.apply {
            this.state = state
            updateTile()
        }
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Log.d(TAG, "Tile removed from Quick Settings")
        prefs.edit().putBoolean(KEY_SERVICE_ACTIVE, false).apply()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "Tile added to Quick Settings")
        // 首次添加时更新状态
        val isRunning = FloatTimeService.isRunning()
        qsTile?.apply {
            state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }
}
