package com.liuml.apptimelimiter.update

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class SingleFlightCallbacksTest {
    @Test fun concurrentChecksHaveOneRequestAndAllReceiveItsResult() {
        val requests = SingleFlightCallbacks<Int>()
        val winners = AtomicInteger()
        val responses = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..50).map { pool.submit {
                if (requests.join { responses.addAndGet(it) }) winners.incrementAndGet()
            } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, winners.get())
            assertTrue(requests.isRunning())
            requests.drain().forEach { it(2) }
            assertEquals(100, responses.get())
            assertFalse(requests.isRunning())
            assertTrue(requests.join {})
        } finally { pool.shutdownNow() }
    }
}
