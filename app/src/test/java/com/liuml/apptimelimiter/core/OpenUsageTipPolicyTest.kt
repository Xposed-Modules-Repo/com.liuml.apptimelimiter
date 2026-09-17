package com.liuml.apptimelimiter.core

import org.junit.Assert.*
import org.junit.Test

class OpenUsageTipPolicyTest {
    @Test fun `entry dedup rejects rapid reentry and clock rollback`() {
        assertTrue(OpenUsageTipPolicy.mayShow(100, null))
        assertFalse(OpenUsageTipPolicy.mayShow(200, 100))
        assertFalse(OpenUsageTipPolicy.mayShow(50, 100))
        assertTrue(OpenUsageTipPolicy.mayShow(30_100, 100))
    }
    @Test fun `unknown usage is omitted and PIN duration is human readable`() {
        assertEquals("临时放行剩余 1 小时", OpenUsageTipPolicy.message("抖音", null, 3_600_000, true, false))
        assertEquals("今天已使用 抖音 35 分钟 · 可用时间剩余 8 分钟",
            OpenUsageTipPolicy.message("抖音", 2_100_000, 480_000, false, false))
        assertFalse(OpenUsageTipPolicy.message("App", null, null, false, true).contains("0"))
    }
}
