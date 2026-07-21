package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupUsagePolicyTest {
    @Test
    fun `uses the stronger source independently for every member`() {
        val total = GroupUsagePolicy.authoritativeTotalMillis(
            packageNames = listOf("a", "b"),
            systemDurations = mapOf("a" to 10_000L, "b" to 2_000L),
            moduleDurations = mapOf("a" to 8_000L, "b" to 7_000L),
        )

        assertEquals(17_000L, total)
    }

    @Test
    fun `negative and overflowing values are safe`() {
        assertEquals(
            Long.MAX_VALUE,
            GroupUsagePolicy.authoritativeTotalMillis(
                packageNames = listOf("a", "b"),
                systemDurations = mapOf("a" to Long.MAX_VALUE, "b" to 1L),
                moduleDurations = mapOf("a" to -1L),
            ),
        )
    }
}
