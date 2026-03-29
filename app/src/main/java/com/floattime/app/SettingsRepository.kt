package com.floattime.app

import android.content.Context
import android.content.SharedPreferences

/**
 * SettingsRepository — Settings access layer
 * ECC clean-architecture: encapsulates SharedPreferences away from UI layer.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("floattime_prefs", Context.MODE_PRIVATE)

    // --- Time Source ---
    var timeSource: String
        get() = prefs.getString("time_source", "local") ?: "local"
        set(value) = prefs.edit().putString("time_source", value).apply()

    // --- Super Island ---
    var superIslandEnabled: Boolean
        get() = prefs.getBoolean("super_island_enabled", false)
        set(value) = prefs.edit().putBoolean("super_island_enabled", value).apply()

    // --- Floating Window ---
    var floatWindowEnabled: Boolean
        get() = prefs.getBoolean("float_window_enabled", true)
        set(value) = prefs.edit().putBoolean("float_window_enabled", value).apply()

    // --- Theme ---
    var theme: String
        get() = prefs.getString("theme", "auto") ?: "auto"
        set(value) = prefs.edit().putString("theme", value).apply()

    // --- Service state ---
    var isServiceRunning: Boolean
        get() = prefs.getBoolean("is_service_running", false)
        set(value) = prefs.edit().putBoolean("is_service_running", value).apply()

    // --- General purpose ---
    fun getString(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    fun putString(key: String, value: String) =
        prefs.edit().putString(key, value).apply()

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        prefs.getBoolean(key, default)

    fun putBoolean(key: String, value: Boolean) =
        prefs.edit().putBoolean(key, value).apply()
}
