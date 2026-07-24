package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaIncidentPolicyTest {
    @Test
    fun `group daily incident is shared across members and processes`() {
        val first = incident(
            packageName = "app.a",
            processSession = "process-a",
            kinds = setOf(QuotaKind.GROUP_DAILY),
        )
        val second = incident(
            packageName = "app.b",
            processSession = "process-b",
            kinds = setOf(QuotaKind.GROUP_DAILY),
        )

        assertEquals(first, second)
    }

    @Test
    fun `group daily incident changes after midnight`() {
        val first = incident(day = "2026-07-24", kinds = setOf(QuotaKind.GROUP_DAILY))
        val second = incident(day = "2026-07-25", kinds = setOf(QuotaKind.GROUP_DAILY))

        assertFalse(first == second)
    }

    @Test
    fun `group per launch incident is scoped to member process session`() {
        val first = incident(
            packageName = "app.a",
            processSession = "session-1",
            kinds = setOf(QuotaKind.GROUP_PER_LAUNCH),
        )
        val sameSession = incident(
            packageName = "app.a",
            processSession = "session-1",
            kinds = setOf(QuotaKind.GROUP_PER_LAUNCH),
        )
        val nextSession = incident(
            packageName = "app.a",
            processSession = "session-2",
            kinds = setOf(QuotaKind.GROUP_PER_LAUNCH),
        )

        assertEquals(first, sameSession)
        assertFalse(first == nextSession)
    }

    @Test
    fun `same incident never restarts expired cooldown`() {
        val first = SharedCooldownPolicy.claim(
            existingRecord = SharedCooldownRecord(),
            handledIncidentIds = emptyList(),
            incidentId = "daily",
            sourcePackage = "app.a",
            occurredAtMillis = 1_000L,
            durationMillis = 60_000L,
            nowMillis = 1_000L,
        )
        val duplicate = SharedCooldownPolicy.claim(
            existingRecord = first.record,
            handledIncidentIds = first.handledIncidentIds,
            incidentId = "daily",
            sourcePackage = "app.a",
            occurredAtMillis = 1_000L,
            durationMillis = 60_000L,
            nowMillis = 70_000L,
        )

        assertTrue(first.cooldownStarted)
        assertEquals(61_000L, first.record.endsAtMillis)
        assertFalse(duplicate.isNewIncident)
        assertFalse(duplicate.cooldownStarted)
        assertEquals(61_000L, duplicate.record.endsAtMillis)
    }

    @Test
    fun `simultaneous second incident is absorbed without refreshing end`() {
        val first = SharedCooldownPolicy.claim(
            existingRecord = SharedCooldownRecord(),
            handledIncidentIds = emptyList(),
            incidentId = "member-a",
            sourcePackage = "app.a",
            occurredAtMillis = 10_000L,
            durationMillis = 60_000L,
            nowMillis = 10_000L,
        )
        val second = SharedCooldownPolicy.claim(
            existingRecord = first.record,
            handledIncidentIds = first.handledIncidentIds,
            incidentId = "member-b",
            sourcePackage = "app.b",
            occurredAtMillis = 12_000L,
            durationMillis = 60_000L,
            nowMillis = 12_000L,
        )

        assertEquals(SharedCooldownClaimStatus.ABSORBED_BY_ACTIVE, second.status)
        assertEquals(first.record.endsAtMillis, second.record.endsAtMillis)
        assertTrue(second.isNewIncident)
        assertFalse(second.cooldownStarted)
        assertTrue("member-b" in second.handledIncidentIds)
    }

    @Test
    fun `new incident after expiry starts from occurrence without resetting on retry`() {
        val claim = SharedCooldownPolicy.claim(
            existingRecord = SharedCooldownRecord(),
            handledIncidentIds = listOf("old"),
            incidentId = "new",
            sourcePackage = "app.a",
            occurredAtMillis = 100_000L,
            durationMillis = 60_000L,
            nowMillis = 110_000L,
        )

        assertTrue(claim.cooldownStarted)
        assertEquals(160_000L, claim.record.endsAtMillis)
    }

    private fun incident(
        packageName: String = "app.a",
        processSession: String = "session",
        day: String = "2026-07-24",
        kinds: Set<QuotaKind>,
    ): String? = QuotaIncidentPolicy.incidentId(
        packageName = packageName,
        ruleVersion = 10L,
        groupId = "group",
        groupVersion = 20L,
        dayToken = day,
        processSessionId = processSession,
        reachedKinds = kinds,
    )
}
