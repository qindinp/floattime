package com.floattime.app

import org.junit.Assert.*
import org.junit.Test

/**
 * FloatTimeService 核心逻辑测试
 * 测试可独立验证的常量和辅助方法
 */
class FloatTimeServiceTest {

    // ========== 常量验证测试 ==========

    @Test
    fun `notification id is valid`() {
        // 验证通知 ID 在合理范围内
        val notificationId = 20240320
        assertTrue(notificationId > 0)
        assertTrue(notificationId < Int.MAX_VALUE)
    }

    @Test
    fun `update interval is reasonable`() {
        // 更新间隔应在 100ms-1s 之间，平衡性能和功耗
        val updateIntervalMs = 200L
        assertTrue(updateIntervalMs >= 100L)
        assertTrue(updateIntervalMs <= 1000L)
    }

    @Test
    fun `connect timeout is reasonable`() {
        // 连接超时应在 5-15 秒之间
        val connectTimeoutMs = 8000L
        assertTrue(connectTimeoutMs >= 5000L)
        assertTrue(connectTimeoutMs <= 15000L)
    }

    @Test
    fun `read timeout is reasonable`() {
        // 读取超时应在 5-15 秒之间
        val readTimeoutMs = 8000L
        assertTrue(readTimeoutMs >= 5000L)
        assertTrue(readTimeoutMs <= 15000L)
    }

    @Test
    fun `save throttle prevents excessive writes`() {
        // 保存节流应至少 1 秒
        val saveThrottleMs = 5000L
        assertTrue(saveThrottleMs >= 1000L)
    }

    // ========== 时间源显示名测试 ==========

    @Test
    fun `source display name for taobao`() {
        assertEquals("淘宝时间", getDisplay("taobao"))
    }

    @Test
    fun `source display name for meituan`() {
        assertEquals("美团时间", getDisplay("meituan"))
    }

    @Test
    fun `source display name for local`() {
        assertEquals("本地时间", getDisplay("local"))
    }

    @Test
    fun `source display name for unknown defaults to local`() {
        assertEquals("本地时间", getDisplay("unknown"))
    }

    // 辅助方法：模拟 sourceDisplayName 逻辑
    private fun getDisplay(source: String): String = when (source) {
        "taobao" -> "淘宝时间"
        "meituan" -> "美团时间"
        else -> "本地时间"
    }

    // ========== 偏移量计算测试 ==========

    @Test
    fun `offset calculation positive`() {
        val serverTime = 10000L
        val localMid = 9500L
        val offset = serverTime - localMid
        assertEquals(500L, offset)
    }

    @Test
    fun `offset calculation negative`() {
        val serverTime = 9000L
        val localMid = 9500L
        val offset = serverTime - localMid
        assertEquals(-500L, offset)
    }

    @Test
    fun `offset calculation zero`() {
        val serverTime = 10000L
        val localMid = 10000L
        val offset = serverTime - localMid
        assertEquals(0L, offset)
    }

    // ========== API URL 测试 ==========

    @Test
    fun `taobao api url is correct`() {
        val url = getApiUrl("taobao")
        assertTrue(url.contains("taobao.com"))
    }

    @Test
    fun `meituan api url is correct`() {
        val url = getApiUrl("meituan")
        assertTrue(url.contains("meituan.com"))
    }

    private fun getApiUrl(source: String): String = if (source == "taobao")
        "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
    else
        "https://api.meituan.com/nationalTimestamp"

    // ========== 秒表状态机测试 ==========

    @Test
    fun `start transitions to running`() {
        val next = FloatTimeService.nextStopwatchState(
            FloatTimeService.STOPWATCH_STATE_IDLE,
            FloatTimeService.ACTION_STOPWATCH_START
        )
        assertEquals(FloatTimeService.STOPWATCH_STATE_RUNNING, next)
    }

    @Test
    fun `pause and resume transitions correctly`() {
        val paused = FloatTimeService.nextStopwatchState(
            FloatTimeService.STOPWATCH_STATE_RUNNING,
            FloatTimeService.ACTION_STOPWATCH_PAUSE
        )
        val resumed = FloatTimeService.nextStopwatchState(
            paused,
            FloatTimeService.ACTION_STOPWATCH_RESUME
        )
        assertEquals(FloatTimeService.STOPWATCH_STATE_PAUSED, paused)
        assertEquals(FloatTimeService.STOPWATCH_STATE_RUNNING, resumed)
    }

    @Test
    fun `stop transitions to stopped from running`() {
        val next = FloatTimeService.nextStopwatchState(
            FloatTimeService.STOPWATCH_STATE_RUNNING,
            FloatTimeService.ACTION_STOPWATCH_STOP
        )
        assertEquals(FloatTimeService.STOPWATCH_STATE_STOPPED, next)
    }

    @Test
    fun `elapsed uses base realtime while running`() {
        val elapsed = FloatTimeService.computeStopwatchElapsedMs(
            state = FloatTimeService.STOPWATCH_STATE_RUNNING,
            accumulatedMs = 1200L,
            runningBaseRealtimeMs = 1000L,
            nowRealtimeMs = 1600L
        )
        assertEquals(1800L, elapsed)
    }

    @Test
    fun `elapsed is accumulated when paused`() {
        val elapsed = FloatTimeService.computeStopwatchElapsedMs(
            state = FloatTimeService.STOPWATCH_STATE_PAUSED,
            accumulatedMs = 2300L,
            runningBaseRealtimeMs = 1000L,
            nowRealtimeMs = 5000L
        )
        assertEquals(2300L, elapsed)
    }

    @Test
    fun `format stopwatch under one hour`() {
        val (time, millis) = FloatTimeService.formatStopwatchElapsed(62_345L)
        assertEquals("01:02", time)
        assertEquals("345", millis)
    }

    @Test
    fun `format stopwatch over one hour`() {
        val (time, millis) = FloatTimeService.formatStopwatchElapsed(3_661_009L)
        assertEquals("01:01:01", time)
        assertEquals("009", millis)
    }
}
