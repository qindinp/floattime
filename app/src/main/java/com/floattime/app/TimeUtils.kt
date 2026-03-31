package com.floattime.app

import org.json.JSONObject
import java.util.Calendar

/**
 * TimeUtils — 纯 Kotlin 时间工具，零 Android 依赖，可直接单元测试。
 */
object TimeUtils {

    /**
     * 解析服务器时间戳（毫秒）。
     * 支持三种 JSON 格式:
     *   { "data": { "t": "1711728000000" } }
     *   { "t": 1711728000 }
     *   { "timestamp": 1711728000 }
     *
     * @return 毫秒级时间戳，解析失败返回 0
     */
    fun parseServerTime(response: String): Long {
        try {
            val json = JSONObject(response)

            // 格式 1: { "data": { "t": "..." } } — 淘宝
            if (json.has("data")) {
                val data = json.getJSONObject("data")
                if (data.has("t")) {
                    val ts = data.getString("t").toLongOrNull() ?: 0
                    if (ts > 0) return normalizeToMs(ts)
                }
            }

            // 格式 2: { "t": 123 } — 备选
            if (json.has("t")) {
                val ts = json.getLong("t")
                if (ts > 0) return normalizeToMs(ts)
            }

            // 格式 3: { "timestamp": 123 } — 美团
            if (json.has("timestamp")) {
                val ts = json.getLong("timestamp")
                if (ts > 0) return normalizeToMs(ts)
            }
        } catch (_: Exception) {
        }
        return 0
    }

    /**
     * 根据主题模式判断是否夜间。
     * @param themeMode 0=自动, 1=浅色, 2=深色
     * @param hour 当前小时（0-23），仅自动模式使用，默认取系统时间
     */
    fun calcNightMode(themeMode: Int, hour: Int = currentHour()): Boolean = when (themeMode) {
        0 -> hour >= 19 || hour < 7
        2 -> true
        else -> false
    }

    /**
     * 秒级时间差（用于时间同步偏移计算）。
     */
    fun calcOffsetMs(serverTimeMs: Long, localMidMs: Long): Long =
        serverTimeMs - localMidMs

    /** 秒转毫秒，已经是毫秒的直接返回 */
    private fun normalizeToMs(ts: Long): Long =
        if (ts < 10_000_000_000L) ts * 1000 else ts

    private fun currentHour(): Int =
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
}
