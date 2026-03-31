package com.floattime.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TimeUtils 单元测试 — 纯 JVM，无 Android 依赖
 */
class TimeUtilsTest {

    // ─────────────────────────────────────────────
    //  parseServerTime
    // ─────────────────────────────────────────────

    @Test
    fun `parseServerTime - taobao format with string t in data`() {
        val json = """{"data":{"t":"1711728000000"}}"""
        assertEquals(1711728000000L, TimeUtils.parseServerTime(json))
    }

    @Test
    fun `parseServerTime - taobao format with seconds t in data`() {
        // 秒级时间戳应自动转毫秒
        val json = """{"data":{"t":"1711728000"}}"""
        assertEquals(1711728000000L, TimeUtils.parseServerTime(json))
    }

    @Test
    fun `parseServerTime - flat t field millis`() {
        val json = """{"t":1711728000000}"""
        assertEquals(1711728000000L, TimeUtils.parseServerTime(json))
    }

    @Test
    fun `parseServerTime - flat t field seconds`() {
        val json = """{"t":1711728000}"""
        assertEquals(1711728000000L, TimeUtils.parseServerTime(json))
    }

    @Test
    fun `parseServerTime - meituan timestamp field`() {
        val json = """{"timestamp":1711728000000}"""
        assertEquals(1711728000000L, TimeUtils.parseServerTime(json))
    }

    @Test
    fun `parseServerTime - meituan timestamp seconds`() {
        val json = """{"timestamp":1711728000}"""
        assertEquals(1711728000000L, TimeUtils.parseServerTime(json))
    }

    @Test
    fun `parseServerTime - empty json returns 0`() {
        assertEquals(0L, TimeUtils.parseServerTime("{}"))
    }

    @Test
    fun `parseServerTime - malformed json returns 0`() {
        assertEquals(0L, TimeUtils.parseServerTime("not json"))
    }

    @Test
    fun `parseServerTime - zero t returns 0`() {
        val json = """{"data":{"t":"0"}}"""
        assertEquals(0L, TimeUtils.parseServerTime(json))
    }

    @Test
    fun `parseServerTime - negative t returns 0`() {
        val json = """{"data":{"t":"-1"}}"""
        assertEquals(0L, TimeUtils.parseServerTime(json))
    }

    @Test
    fun `parseServerTime - data field without t returns 0`() {
        val json = """{"data":{"other":"value"}}"""
        assertEquals(0L, TimeUtils.parseServerTime(json))
    }

    // ─────────────────────────────────────────────
    //  calcNightMode
    // ─────────────────────────────────────────────

    @Test
    fun `calcNightMode - mode 2 always dark`() {
        // 深色模式：无论什么时间都是夜间
        for (hour in 0..23) {
            assertTrue("hour=$hour should be night in mode 2",
                TimeUtils.calcNightMode(2, hour))
        }
    }

    @Test
    fun `calcNightMode - mode 1 always light`() {
        // 浅色模式：无论什么时间都不是夜间
        for (hour in 0..23) {
            assertEquals("hour=$hour should be light in mode 1",
                false, TimeUtils.calcNightMode(1, hour))
        }
    }

    @Test
    fun `calcNightMode - mode 0 auto night hours 19-23`() {
        for (hour in 19..23) {
            assertTrue("hour=$hour should be night", TimeUtils.calcNightMode(0, hour))
        }
    }

    @Test
    fun `calcNightMode - mode 0 auto night hours 0-6`() {
        for (hour in 0..6) {
            assertTrue("hour=$hour should be night", TimeUtils.calcNightMode(0, hour))
        }
    }

    @Test
    fun `calcNightMode - mode 0 auto day hours 7-18`() {
        for (hour in 7..18) {
            assertEquals("hour=$hour should be day", false, TimeUtils.calcNightMode(0, hour))
        }
    }

    @Test
    fun `calcNightMode - boundary hour 7 is day`() {
        assertEquals(false, TimeUtils.calcNightMode(0, 7))
    }

    @Test
    fun `calcNightMode - boundary hour 19 is night`() {
        assertTrue(TimeUtils.calcNightMode(0, 19))
    }

    // ─────────────────────────────────────────────
    //  calcOffsetMs
    // ─────────────────────────────────────────────

    @Test
    fun `calcOffsetMs - positive offset`() {
        assertEquals(500L, TimeUtils.calcOffsetMs(1000L, 500L))
    }

    @Test
    fun `calcOffsetMs - negative offset`() {
        assertEquals(-200L, TimeUtils.calcOffsetMs(800L, 1000L))
    }

    @Test
    fun `calcOffsetMs - zero offset`() {
        assertEquals(0L, TimeUtils.calcOffsetMs(1000L, 1000L))
    }
}
