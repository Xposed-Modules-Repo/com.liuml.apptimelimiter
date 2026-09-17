package com.liuml.apptimelimiter.security

import com.liuml.apptimelimiter.core.TemporaryOverrideIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentAuthStoreTest {
    private val identity = TemporaryOverrideIdentity("app.a", "session-a", 1L, 2L, 3L)

    @Test fun `pending PIN starts only on foreground activation and survives screen off and process change`() {
        ParentAuthStore.issue("pending", identity, "incident", "QUOTA", 1_000L)
        ParentAuthStore.consumeForUi("pending", 1_001L)
        assertNotNull(ParentAuthStore.complete("pending", true, 3_600_000L, 1_002L, 10_000L, deferActivation = true))
        assertNull(ParentAuthStore.getOverride(identity, false, 1_003L, 20_000L, activate = true))
        assertNull(ParentAuthStore.getOverride(identity, true, 1_004L, 30_000L, activate = true))
        assertNull(ParentAuthStore.activateOverride(identity, true, 30_000L, foregroundVerified = false))
        assertNull(ParentAuthStore.activateOverride(identity.copy(processSessionId = "background"), true, 30_000L, foregroundVerified = true))
        val active = ParentAuthStore.activateOverride(identity, true, 30_000L, foregroundVerified = true)!!
        assertEquals(3_630_000L, active.expiresAtElapsedMillis)
        val restored = ParentAuthStore.getOverride(identity.copy(processSessionId = "new"), false, 2_000L, 60_000L)!!
        assertEquals(active.expiresAtElapsedMillis, restored.expiresAtElapsedMillis)
        assertEquals(active, ParentAuthStore.getOverride(identity, true, 2_001L, 60_001L, activate = true))
        assertNull(ParentAuthStore.getOverride(identity, true, 2_002L, 3_630_000L, activate = true))
    }

    @Test fun `global first PIN wins atomically and second requires reward without double counting`() {
        val now = 1_900_000_000_000L
        val a = identity.copy(packageName = "quota.a")
        val b = identity.copy(packageName = "quota.b")
        ParentAuthStore.issue("qa", a, "a", "QUOTA", now)
        ParentAuthStore.issue("qb", b, "b", "QUOTA", now)
        ParentAuthStore.consumeForUi("qa", now + 1)
        ParentAuthStore.consumeForUi("qb", now + 1)
        assertNotNull(ParentAuthStore.complete("qa", true, 60_000, now + 2, 1_000, enforceDailyAd = true))
        assertNull(ParentAuthStore.complete("qb", true, 60_000, now + 2, 1_000, enforceDailyAd = true))
        assertNotNull(ParentAuthStore.markVerifiedWaitingForAd("qb", now + 3))
        assertNull(ParentAuthStore.complete("qb", true, 60_000, now + 4, 1_000, enforceDailyAd = true))
        assertNotNull(ParentAuthStore.markAdRewarded("qb", now + 5))
        assertNotNull(ParentAuthStore.complete("qb", true, 60_000, now + 6, 1_000, enforceDailyAd = true))
        assertNotNull(ParentAuthStore.complete("qb", true, 60_000, now + 7, 1_000, enforceDailyAd = true))
        assertFalse(ParentAuthStore.isAdRequired(now + 86_400_000))
    }

    @After
    fun clear() = ParentAuthStore.resetForTests()

    @Test fun `legacy query hints never activate or renew pending reservation`() {
        val now = 1_900_000_000_000L
        ParentAuthStore.issue("readonly", identity, "event", "QUOTA", now)
        ParentAuthStore.consumeForUi("readonly", now + 1)
        ParentAuthStore.complete("readonly", true, 60_000, now + 2, 1_000,
            enforceDailyAd = true, deferActivation = true)
        val pending = ParentAuthStore.pendingOverride(identity, 1_001)!!
        repeat(20) { index ->
            assertNull(ParentAuthStore.getOverride(identity, true, now + 3 + index, 2_000L + index, activate = true))
            assertEquals(pending, ParentAuthStore.pendingOverride(identity, 2_000L + index))
        }
        assertNull(ParentAuthStore.pendingOverride(identity, 121_000))
        assertNull(ParentAuthStore.activateOverride(identity, true, 121_000, foregroundVerified = true))
        assertFalse(ParentAuthStore.isAdRequired(now + 120_003, 121_001))
    }

    @Test fun `activation needs current identity foreground evidence and interactive screen`() {
        ParentAuthStore.issue("evidence", identity, "event", "QUOTA", 1_000)
        ParentAuthStore.consumeForUi("evidence", 1_001)
        ParentAuthStore.complete("evidence", true, 60_000, 1_002, 10_000, deferActivation = true)
        assertNull(ParentAuthStore.activateOverride(identity, false, 11_000, foregroundVerified = true))
        assertNull(ParentAuthStore.activateOverride(identity, true, 11_000, foregroundVerified = false))
        listOf(identity.copy(packageName = "other.app"), identity.copy(processSessionId = "other"),
            identity.copy(ruleVersion = 99), identity.copy(groupVersion = 99),
            identity.copy(protectionModeGeneration = 99)).forEach {
            assertNull(ParentAuthStore.activateOverride(it, true, 11_000, foregroundVerified = true))
        }
        assertNotNull(ParentAuthStore.pendingOverride(identity, 11_001))
        val active = ParentAuthStore.activateOverride(identity, true, 12_000, foregroundVerified = true)!!
        assertEquals(72_000L, active.expiresAtElapsedMillis)
        assertNull(ParentAuthStore.pendingOverride(identity, 12_001))
        assertEquals(active, ParentAuthStore.activateOverride(identity, true, 20_000, foregroundVerified = true))
    }

    @Test fun `parallel activation has one fixed cutoff and cannot stack duration`() {
        ParentAuthStore.issue("parallel", identity, "event", "QUOTA", 1_000)
        ParentAuthStore.consumeForUi("parallel", 1_001)
        ParentAuthStore.complete("parallel", true, 60_000, 1_002, 10_000, deferActivation = true)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val grants = pool.invokeAll((0..15).map { java.util.concurrent.Callable {
                ParentAuthStore.activateOverride(identity, true, 20_000L, foregroundVerified = true)
            } }).map { it.get() }
            assertEquals(16, grants.filterNotNull().size)
            assertEquals(1, grants.map { it?.expiresAtElapsedMillis }.toSet().size)
            assertEquals(80_000L, grants.first()!!.expiresAtElapsedMillis)
        } finally { pool.shutdownNow() }
    }

    @Test fun `unactivated free reservation expires without spending daily allowance`() {
        val now = 1_900_000_000_000L
        ParentAuthStore.issue("reserved", identity, "event", "QUOTA", now)
        ParentAuthStore.consumeForUi("reserved", now + 1)
        assertNotNull(ParentAuthStore.complete("reserved", true, 60_000, now + 2, 1_000,
            enforceDailyAd = true, deferActivation = true))
        assertTrue(ParentAuthStore.isAdRequired(now + 3, 1_001))
        assertFalse(ParentAuthStore.isAdRequired(now + 120_003, 121_001))
        assertNull(ParentAuthStore.getOverride(identity, true, now + 120_003, 121_001, activate = true))
    }

    @Test fun `activation spends reservation once and subsequent entry retains deadline`() {
        val now = 1_900_000_000_000L
        ParentAuthStore.issue("activate", identity, "event", "QUOTA", now)
        ParentAuthStore.consumeForUi("activate", now + 1)
        ParentAuthStore.complete("activate", true, 60_000, now + 2, 1_000,
            enforceDailyAd = true, deferActivation = true)
        val active = ParentAuthStore.activateOverride(identity, true, 2_000, foregroundVerified = true)!!
        assertEquals(62_000L, active.expiresAtElapsedMillis)
        assertEquals("", active.pendingQuotaDay)
        assertTrue(ParentAuthStore.isAdRequired(now + 200_000, 201_000))
        assertEquals(active, ParentAuthStore.getOverride(identity, false, now + 4, 3_000))
        assertFalse(ParentAuthStore.needsAdForCompletion("activate", now + 4, 3_000))
    }

    @Test fun `concurrent free reservations have exactly one winner`() {
        val now = 1_900_000_000_000L
        (0..7).forEach {
            ParentAuthStore.issue("race$it", identity.copy(packageName = "app.p$it"), "e$it", "QUOTA", now)
            ParentAuthStore.consumeForUi("race$it", now + 1)
        }
        val gate = java.util.concurrent.CountDownLatch(1)
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val threads = (0..7).map { index -> Thread {
            gate.await()
            if (ParentAuthStore.complete("race$index", true, 60_000, now + 2, 1_000,
                    enforceDailyAd = true, deferActivation = true) != null) winners.incrementAndGet()
        }.apply { start() } }
        gate.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(1, winners.get())
    }

    @Test
    fun `rule changed while PIN was entered cannot complete or retain an override`() {
        ParentAuthStore.issue("stale", identity, "incident", "QUOTA", 1_000L)
        ParentAuthStore.consumeForUi("stale", 1_001L)
        assertNull(ParentAuthStore.complete("stale", true, 60_000L, 1_002L, 10_000L) { false })
        assertEquals(ParentAuthStatus.INVALID, ParentAuthStore.status("stale", "app.a", "session-a", 1_003L))
        assertNull(ParentAuthStore.getOverride(identity, true, 1_003L, 10_001L))
    }

    @Test
    fun `challenge is package bound and consumed once`() {
        ParentAuthStore.issue("token", identity, "incident", "QUOTA", 1_000L)
        assertNotNull(ParentAuthStore.consumeForUi("token", 2_000L))
        assertNull(ParentAuthStore.consumeForUi("token", 2_001L))
        assertEquals(
            ParentAuthStatus.INVALID,
            ParentAuthStore.status("token", "app.b", "session-a", 2_002L),
        )
    }

    @Test
    fun `successful challenge survives a target process recreation until expiry`() {
        ParentAuthStore.issue("token", identity, "incident", "QUOTA", 1_000L)
        ParentAuthStore.consumeForUi("token", 2_000L)
        ParentAuthStore.complete("token", true, 60_000L, 2_001L, 10_000L)
        assertNotNull(ParentAuthStore.getOverride(identity, true, 2_002L, 10_001L))
        assertNotNull(
            ParentAuthStore.getOverride(
                identity = identity.copy(processSessionId = "session-recreated"),
                screenInteractive = true,
                nowMillis = 2_002L,
                nowElapsedMillis = 10_001L,
            ),
        )
        assertNull(
            ParentAuthStore.getOverride(
                identity = identity.copy(ruleVersion = 9L),
                screenInteractive = true,
                nowMillis = 2_002L,
                nowElapsedMillis = 10_001L,
            ),
        )
        assertFalse(ParentAuthStore.revoke("app.a", "session-recreated"))
        assertNotNull(ParentAuthStore.getOverride(identity, true, 2_003L, 10_002L))
        assertTrue(ParentAuthStore.revoke("app.a", "session-a"))
        assertNull(ParentAuthStore.getOverride(identity, true, 2_003L, 10_002L))
    }

    @Test
    fun `expired waiting challenge times out and cannot grant`() {
        ParentAuthStore.issue("token", identity, "incident", "SCHEDULE", 1_000L)
        assertEquals(
            ParentAuthStatus.TIMED_OUT,
            ParentAuthStore.status("token", "app.a", "session-a", 32_000L),
        )
        assertNull(ParentAuthStore.complete("token", true, 60_000L, 32_001L, 1_000L))
    }

    @Test
    fun `verified PIN requires an ad reward before it can grant`() {
        ParentAuthStore.issue("token", identity, "incident", "QUOTA", 1_000L)
        ParentAuthStore.consumeForUi("token", 1_001L)
        assertEquals(
            ParentAuthStatus.VERIFIED_WAITING_AD,
            ParentAuthStore.markVerifiedWaitingForAd("token", 1_002L)?.status,
        )
        assertNull(ParentAuthStore.complete("token", true, 60_000L, 1_003L, 5_000L))
        assertEquals(
            ParentAuthStatus.AD_REWARDED,
            ParentAuthStore.markAdRewarded("token", 1_004L)?.status,
        )
        assertNotNull(ParentAuthStore.complete("token", true, 60_000L, 1_005L, 5_001L))
        assertNotNull(ParentAuthStore.complete("token", true, 60_000L, 1_006L, 5_002L))
    }

    @Test
    fun `override expires on monotonic deadline and duplicate challenge invalidates old one`() {
        ParentAuthStore.issue("old", identity, "incident-1", "QUOTA", 1_000L)
        ParentAuthStore.issue("new", identity, "incident-2", "QUOTA", 1_100L)
        assertEquals(
            ParentAuthStatus.INVALID,
            ParentAuthStore.status("old", "app.a", "session-a", 1_200L),
        )
        ParentAuthStore.consumeForUi("new", 1_300L)
        ParentAuthStore.complete("new", true, 60_000L, 1_400L, 5_000L)
        assertNotNull(ParentAuthStore.getOverride(identity, true, 1_500L, 64_999L))
        assertNull(ParentAuthStore.getOverride(identity, true, 1_600L, 65_000L))
    }
}
