package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeQuotePolicyTest {
    @Test
    fun `custom quotes are trimmed deduplicated and bounded`() {
        val long = "时".repeat(TimeQuotePolicy.MAX_QUOTE_CODE_POINTS + 5)
        val extra = (1..25).joinToString("\n") { "短句$it" }
        val values = TimeQuotePolicy.parseCustomQuotes(
            "  一寸光阴  \n\n一寸光阴\n$long\n$extra",
        )

        assertEquals(TimeQuotePolicy.MAX_CUSTOM_QUOTES, values.size)
        assertEquals("一寸光阴", values.first())
        assertEquals(
            TimeQuotePolicy.MAX_QUOTE_CODE_POINTS,
            values[1].codePointCount(0, values[1].length),
        )
    }

    @Test
    fun `selection is stable for one event seed`() {
        val first = TimeQuotePolicy.select(true, true, listOf("自定义"), false, "incident-1")
        val second = TimeQuotePolicy.select(true, true, listOf("自定义"), false, "incident-1")

        assertEquals(first, second)
        assertTrue(first?.isNotBlank() == true)
    }

    @Test
    fun `disabled or empty pool yields no quote`() {
        assertNull(TimeQuotePolicy.select(false, true, emptyList(), false, "a"))
        assertNull(TimeQuotePolicy.select(true, false, emptyList(), false, "a"))
    }
}
