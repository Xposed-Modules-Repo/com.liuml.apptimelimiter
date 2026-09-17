package com.liuml.apptimelimiter

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class StatisticsIdempotencyInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = "audit_statistics_${UUID.randomUUID()}"
    private val isolated = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(storage, mode)
    }

    @After fun cleanup() { base.deleteSharedPreferences(storage) }

    @Test fun repeatedReminderIsCountedOnceAcrossRepositoryInstances() {
        val day = LocalDate.now()
        repeat(3) { assertTrue(UsageStatsRepository(isolated).recordReminderEvent("app.a", day, "warning")) }
        assertEquals(1, UsageStatsRepository(isolated).summaryForDay("app.a", day).reminderCount)
        assertTrue(UsageStatsRepository(isolated).recordReminderEvent("app.b", day, "warning"))
        assertEquals(1, UsageStatsRepository(isolated).summaryForDay("app.b", day).reminderCount)
    }

    @Test fun onlyOneConcurrentMilestoneClaimSucceeds() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..12).map {
                Callable { UsageStatsRepository(isolated).claimUsageMilestoneReminder("app.a", LocalDate.now(), 1) }
            })
            assertEquals(1, results.count { it.get() })
            assertEquals(1, UsageStatsRepository(isolated).summaryToday("app.a").reminderCount)
        } finally { pool.shutdownNow() }
    }

    @Test fun malformedEventCannotIncrementStatistics() {
        assertFalse(UsageStatsRepository(isolated).record("app.a", 0, 0, 1, 0, eventId = "x".repeat(161)))
        assertEquals(0, UsageStatsRepository(isolated).summaryToday("app.a").limitHitCount)
    }
}
