package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MonotonicVersionPolicyTest {
    @Test
    fun `uses wall clock when it is newer`() {
        assertEquals(10_000L, MonotonicVersionPolicy.next(7L, 10_000L))
    }

    @Test
    fun `increments when wall clock moves backward`() {
        assertEquals(101L, MonotonicVersionPolicy.next(100L, 50L))
    }

    @Test
    fun `saturates instead of wrapping at long max`() {
        assertEquals(
            Long.MAX_VALUE,
            MonotonicVersionPolicy.next(Long.MAX_VALUE, 10_000L),
        )
    }
}
