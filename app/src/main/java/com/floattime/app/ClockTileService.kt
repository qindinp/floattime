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
        private const val KEY_STOPWATCH_STATE = "stopwatch_state"
        private const val STATE_RUNNING = "running"
    }

    private lateinit var prefs: SharedPreferences

    override fun onStartListening() {
        super.onStartListening()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val isRunning = try { FloatTimeService.isRunning() } catch (_: Exception) { false }
        prefs.edit().putBoolean(KEY_SERVICE_ACTIVE, isRunning).apply()
        if (!isRunning) {
            prefs.edit().putString(KEY_STOPWATCH_STATE, FloatTimeService.STOPWATCH_STATE_IDLE).apply()
        }
        qsTile?.apply { state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }

        Log.d(TAG, "onStartListening | isRunning=$isRunning")
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "onStopListening")
    }

    override fun onClick() {
        super.onClick()

        val isRunning = try { FloatTimeService.isRunning() } catch (_: Exception) { false }

        if (!isRunning) {
            startServiceAction(FloatTimeService.ACTION_SET_MODE) {
                putExtra("mode", "stopwatch")
            }
            startServiceAction(FloatTimeService.ACTION_STOPWATCH_START)
            prefs.edit().putString(KEY_STOPWATCH_STATE, FloatTimeService.STOPWATCH_STATE_RUNNING).apply()
            applyTileState(Tile.STATE_ACTIVE)
            Log.d(TAG, "Tile clicked: start stopwatch")
            return
        }

        val stopwatchState = prefs.getString(KEY_STOPWATCH_STATE, FloatTimeService.STOPWATCH_STATE_RUNNING)
        if (stopwatchState == STATE_RUNNING) {
            startServiceAction(FloatTimeService.ACTION_STOPWATCH_PAUSE)
            prefs.edit().putString(KEY_STOPWATCH_STATE, FloatTimeService.STOPWATCH_STATE_PAUSED).apply()
            Log.d(TAG, "Tile clicked: pause stopwatch")
        } else {
            startServiceAction(FloatTimeService.ACTION_STOPWATCH_RESUME)
            prefs.edit().putString(KEY_STOPWATCH_STATE, FloatTimeService.STOPWATCH_STATE_RUNNING).apply()
            Log.d(TAG, "Tile clicked: resume stopwatch")
        }
        applyTileState(Tile.STATE_ACTIVE)
    }

    private fun startServiceAction(action: String, config: Intent.() -> Unit = {}) {
        try {
            val intent = Intent(this, FloatTimeService::class.java).apply {
                this.action = action
                config()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startServiceAction failed: ${e.message}")
            applyTileState(Tile.STATE_UNAVAILABLE)
        }
    }

    private fun applyTileState(state: Int) {
        qsTile?.apply {
            this.state = state
            updateTile()
        }
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Log.d(TAG, "Tile removed from Quick Settings")
        prefs.edit()
            .putBoolean(KEY_SERVICE_ACTIVE, false)
            .putString(KEY_STOPWATCH_STATE, FloatTimeService.STOPWATCH_STATE_IDLE)
            .apply()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "Tile added to Quick Settings")
        // 首次添加时更新状态
        val isRunning = try { FloatTimeService.isRunning() } catch (_: Exception) { false }
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
