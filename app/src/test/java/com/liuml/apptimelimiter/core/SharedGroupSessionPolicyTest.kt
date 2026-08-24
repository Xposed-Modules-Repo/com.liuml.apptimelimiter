package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedGroupSessionPolicyTest {
    @Test
    fun `member handoff keeps the same shared allowance`() {
        val first = enter(SharedGroupSessionRecord(), "app-a", now = 1_000L)
        val committed = update(
            first.record,
            SharedGroupSessionAction.COMMIT,
            owner = "app-a",
            expected = first.record.sessionId,
            segmentId = "a-1",
            segmentMillis = 600_000L,
            now = 601_000L,
        )
        val handedOff = enter(committed.record, "app-b", now = 602_000L)

        assertEquals(first.record.sessionId, handedOff.record.sessionId)
        assertEquals(600_000L, handedOff.record.usedMillis)
        assertTrue(handedOff.ownerTransferred)
        assertFalse(handedOff.restarted)
    }

    @Test
    fun `sufficient rest starts a new group session`() {
        val entered = enter(SharedGroupSessionRecord(), "app-a", now = 1_000L)
        val left = update(
            entered.record,
            SharedGroupSessionAction.LEAVE,
            owner = "app-a",
            expected = entered.record.sessionId,
            segmentId = "a-end",
            segmentMillis = 900_000L,
            now = 901_000L,
        )
        val resumed = enter(left.record, "app-b", now = 2_101_000L, gap = 1_200_000L)

        assertTrue(resumed.restarted)
        assertEquals("generated-app-b", resumed.record.sessionId)
        assertEquals(0L, resumed.record.usedMillis)
    }

    @Test
    fun `previous owner can finish its segment after another member takes over`() {
        val entered = enter(SharedGroupSessionRecord(), "app-a", now = 1_000L)
        val handedOff = enter(entered.record, "app-b", now = 2_000L)
        val lateCommit = update(
            handedOff.record,
            SharedGroupSessionAction.COMMIT,
            owner = "app-a",
            expected = entered.record.sessionId,
            segmentId = "late-a",
            segmentMillis = 10_000L,
            now = 3_000L,
        )

        assertFalse(lateCommit.staleRequest)
        assertTrue(lateCommit.segmentAccepted)
        assertEquals(10_000L, lateCommit.record.usedMillis)
        assertEquals("app-b", lateCommit.record.activeOwnerId)
        assertEquals(2_000L, lateCommit.record.ownerLastSeenElapsedMillis)
    }

    @Test
    fun `dead active owner expires after the configured rest`() {
        val entered = enter(SharedGroupSessionRecord(), "app-a", now = 1_000L, gap = 30_000L)
        val resumed = enter(entered.record, "app-b", now = 31_000L, gap = 30_000L)

        assertTrue(resumed.restarted)
        assertEquals("generated-app-b", resumed.record.sessionId)
    }

    @Test
    fun `duplicate segment is idempotent`() {
        val entered = enter(SharedGroupSessionRecord(), "app-a", now = 1_000L)
        val first = update(
            entered.record,
            SharedGroupSessionAction.COMMIT,
            owner = "app-a",
            expected = entered.record.sessionId,
            segmentId = "segment",
            segmentMillis = 5_000L,
            now = 6_000L,
        )
        val duplicate = update(
            first.record,
            SharedGroupSessionAction.COMMIT,
            owner = "app-a",
            expected = first.record.sessionId,
            segmentId = "segment",
            segmentMillis = 5_000L,
            now = 7_000L,
        )

        assertFalse(duplicate.segmentAccepted)
        assertEquals(5_000L, duplicate.record.usedMillis)
    }

    @Test
    fun `boot or group version change resets session`() {
        val entered = enter(SharedGroupSessionRecord(), "app-a", now = 1_000L)
        val bootReset = SharedGroupSessionPolicy.update(
            existing = entered.record,
            action = SharedGroupSessionAction.ENTER,
            groupVersion = 7L,
            bootCount = 10,
            ownerId = "app-a",
            expectedSessionId = "",
            generatedSessionId = "new-boot",
            segmentId = "",
            segmentMillis = 0L,
            nowElapsedMillis = 2_000L,
            resetGapMillis = 30_000L,
        )

        assertTrue(bootReset.restarted)
        assertEquals("new-boot", bootReset.record.sessionId)
    }

    @Test
    fun `sub second remainder is treated as reached`() {
        assertEquals(0L, QuotaBoundaryPolicy.normalizeRemainingMillis(16L))
        assertEquals(251L, QuotaBoundaryPolicy.normalizeRemainingMillis(251L))
    }

    private fun enter(
        record: SharedGroupSessionRecord,
        owner: String,
        now: Long,
        gap: Long = 1_200_000L,
    ) = SharedGroupSessionPolicy.update(
        existing = record,
        action = SharedGroupSessionAction.ENTER,
        groupVersion = 7L,
        bootCount = 9,
        ownerId = owner,
        expectedSessionId = "",
        generatedSessionId = "generated-$owner",
        segmentId = "",
        segmentMillis = 0L,
        nowElapsedMillis = now,
        resetGapMillis = gap,
    )

    private fun update(
        record: SharedGroupSessionRecord,
        action: SharedGroupSessionAction,
        owner: String,
        expected: String,
        segmentId: String,
        segmentMillis: Long,
        now: Long,
    ) = SharedGroupSessionPolicy.update(
        existing = record,
        action = action,
        groupVersion = 7L,
        bootCount = 9,
        ownerId = owner,
        expectedSessionId = expected,
        generatedSessionId = "unused",
        segmentId = segmentId,
        segmentMillis = segmentMillis,
        nowElapsedMillis = now,
        resetGapMillis = 1_200_000L,
    )
}
