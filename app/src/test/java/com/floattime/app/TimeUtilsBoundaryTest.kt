package com.floattime.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeUtilsBoundaryTest {

    @Test
    fun `parseServerTime - extreme large timestamp returns 0`() {
        // 超大时间戳应返回0表示解析失败
        val json = """{"data":{"t":"999999999999999999999"}}"""
        assertEquals(0L, TimeUtils.parseServerTime(json))
    }

    @Test
    fun `calcNightMode - boundary 6am is night`() {
        // 6点属于夜间（自动模式）
        assertTrue(TimeUtils.calcNightMode(0, 6))
    }

    @Test
    fun `calcNightMode - boundary 7am is day`() {
        // 7点开始是白天
        assertEquals(false, TimeUtils.calcNightMode(0, 7))
    }

    @Test
    fun `calcNightMode - boundary 6pm is day`() {
        // 18点仍是白天
        assertEquals(false, TimeUtils.calcNightMode(0, 18))
    }

    @Test
    fun `calcNightMode - boundary 7pm is night`() {
        // 19点开始是夜间
        assertTrue(TimeUtils.calcNightMode(0, 19))
    }

    @Test
    fun `calcNightMode - mode 1 always light`() {
        // 浅色模式：任何时间都是白天
        for (hour in 0..23) {
            assertEquals(false, TimeUtils.calcNightMode(1, hour))
        }
    }

    @Test
    fun `calcNightMode - mode 2 always dark`() {
        // 深色模式：任何时间都是夜间
        for (hour in 0..23) {
            assertTrue(TimeUtils.calcNightMode(2, hour))
        }
    }

    @Test
    fun `parseServerTime - null data returns 0`() {
        val json = """{"data":null}"""
        assertEquals(0L, TimeUtils.parseServerTime(json))
    }

    @Test
    fun `parseServerTime - missing data returns 0`() {
        val json = """{"other":"value"}"""
        assertEquals(0L, TimeUtils.parseServerTime(json))
    }
}