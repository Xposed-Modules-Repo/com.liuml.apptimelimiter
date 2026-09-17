package com.liuml.apptimelimiter.core

import org.junit.Assert.*
import org.junit.Test

class CooldownBootPolicyTest {
    private val original = SharedCooldownRecord(1_000L, 61_000L, "incident", "app",
        10_000L, 70_000L, 3)

    @Test fun rebootWithHigherUptimeStillUsesWallRecovery() {
        val recovered = SharedCooldownPolicy.rebase(original, 31_000L, 50_000L, 4)
        assertEquals(80_000L, recovered.endsAtElapsedMillis)
        assertEquals(4, recovered.bootCount)
        assertEquals(20_000L, SharedCooldownPolicy.remainingMillisDual(recovered, 999_999L, 60_000L))
    }

    @Test fun rollbackRecoveryIsBoundedAndDoesNotRefreshOnRead() {
        val recovered = SharedCooldownPolicy.rebase(original, 0L, 2_000L, 4)
        assertEquals(62_000L, recovered.endsAtElapsedMillis)
        assertEquals(recovered, SharedCooldownPolicy.rebase(recovered, 0L, 22_000L, 4))
        assertEquals(40_000L, SharedCooldownPolicy.remainingMillisDual(recovered, 0L, 22_000L))
    }

    @Test fun legacyRecordMigratesEvenIfElapsedLooksValid() {
        val recovered = SharedCooldownPolicy.rebase(original.copy(bootCount = -1), 41_000L, 50_000L, 4)
        assertEquals(70_000L, recovered.endsAtElapsedMillis)
        assertEquals(4, recovered.bootCount)
    }

    @Test fun expiredRecoveryRemainsExpiredDespiteLaterWallRollback() {
        val recovered = SharedCooldownPolicy.rebase(original, 100_000L, 100L, 4)
        assertEquals(0L, SharedCooldownPolicy.remainingMillisDual(recovered, 0L, 200L))
    }

    @Test(expected = IllegalStateException::class)
    fun unknownBootCannotBeRebased() {
        SharedCooldownPolicy.rebase(original, 100_000L, 100_000L, -1)
    }

    @Test fun unknownIdentityCannotShortenCooldown() {
        assertEquals(60_000L, SharedCooldownPolicy.remainingMillisDual(original, 100_000L, 100_000L, -1))
    }

    @Test fun duplicateAndAbsorbedClaimsPreserveEveryTimestamp() {
        val record = SharedCooldownPolicy.rebase(original, 21_000L, 30_000L, 3)
        for (id in listOf("incident", "another")) {
            val claim = SharedCooldownPolicy.claim(record, listOf("incident"), id, "other",
                22_000L, 60_000L, 22_000L, 31_000L, 3)
            assertEquals(record, claim.record)
        }
    }

    @Test fun recoveredRecordSurvivesSerializationWithoutTrustMarker() {
        val first = SharedCooldownPolicy.rebase(original, 21_000L, 100L, 4)
        val disk = first.copy(validatedBootCount = -1)
        val nextProcess = SharedCooldownPolicy.rebase(disk, 999_999L, 1_100L, 4)
        assertEquals(first.endsAtElapsedMillis, nextProcess.endsAtElapsedMillis)
        assertEquals(39_000L, SharedCooldownPolicy.remainingMillisDual(nextProcess, 999_999L, 1_100L))
    }
}
