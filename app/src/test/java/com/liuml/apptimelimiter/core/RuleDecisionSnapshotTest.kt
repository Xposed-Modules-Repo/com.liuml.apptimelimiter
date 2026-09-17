package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.data.ScheduleWindow
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class RuleDecisionSnapshotTest {
    private val now = ZonedDateTime.parse("2026-09-17T10:00:00+08:00[Asia/Shanghai]")
    private fun dailyWindow(start: Int, end: Int) = listOf(
        ScheduleConstraint(ScheduleMode.ALLOW_ONLY, listOf(ScheduleWindow((1..7).toSet(), start, end))),
    )

    @Test fun allReasonsSurvivePrioritySelection() {
        val snapshot = RuleDecisionSnapshot(true, 5_000, 0, 0, planRemainingMillis = 0)
        assertEquals(listOf(RestrictionReason.SCHEDULE, RestrictionReason.COOLDOWN,
            RestrictionReason.APP_DAILY, RestrictionReason.APP_PER_LAUNCH, RestrictionReason.SESSION_PLAN), snapshot.reasons)
        assertEquals(LimitBlockReason.SCHEDULE, snapshot.gate.blockingReason)
        assertFalse(snapshot.gate.startsCooldownWhenNewlyBlocked)
        assertNull(snapshot.nextThresholdMillis)
    }

    @Test fun groupMembershipSuspendsPersonalQuotas() {
        val snapshot = RuleDecisionSnapshot(appDailyRemainingMillis = 0, appPerLaunchRemainingMillis = 0,
            groupDailyRemainingMillis = 20_000, groupPerLaunchRemainingMillis = 30_000, grouped = true)
        assertNull(snapshot.primaryReason)
        assertEquals(20_000L, snapshot.nextThresholdMillis)
        assertTrue(snapshot.reachedKinds.isEmpty())
    }

    @Test fun groupDailyAndSessionRemainDistinct() {
        val snapshot = RuleDecisionSnapshot(groupDailyRemainingMillis = 0, groupPerLaunchRemainingMillis = 0, grouped = true)
        assertEquals(RestrictionReason.GROUP_DAILY, snapshot.primaryReason)
        assertEquals(setOf(QuotaKind.GROUP_DAILY, QuotaKind.GROUP_PER_LAUNCH), snapshot.reachedKinds)
        assertTrue(snapshot.gate.startsCooldownWhenNewlyBlocked)
    }

    @Test fun planNeverStartsCooldown() {
        val snapshot = RuleDecisionSnapshot(planRemainingMillis = 0)
        assertEquals(RestrictionReason.SESSION_PLAN, snapshot.primaryReason)
        assertFalse(snapshot.gate.startsCooldownWhenNewlyBlocked)
        assertNull(RuleAvailabilityPolicy.nextAvailable(snapshot, now, emptyList()))
    }

    @Test fun quotaWinsPlanTie() {
        val snapshot = RuleDecisionSnapshot(appDailyRemainingMillis = 5_000, planRemainingMillis = 5_000)
        assertFalse(snapshot.planIsNextThreshold)
        assertTrue(snapshot.copy(planRemainingMillis = 4_000).planIsNextThreshold)
        assertEquals(RestrictionReason.APP_DAILY, snapshot.copy(appDailyRemainingMillis = 0, planRemainingMillis = 0).primaryReason)
    }

    @Test fun cooldownEndingOutsideWindowWaitsForNextOpening() {
        val snapshot = RuleDecisionSnapshot(cooldownRemainingMillis = 3 * 60 * 60 * 1000L)
        assertEquals(now.plusDays(1).withHour(9), RuleAvailabilityPolicy.nextAvailable(snapshot, now, dailyWindow(9 * 60, 12 * 60)))
    }

    @Test fun availabilitySkipsOpeningBlockedByAnotherSchedule() {
        val schedules = dailyWindow(11 * 60, 18 * 60) + dailyWindow(14 * 60, 19 * 60)
        val available = RuleAvailabilityPolicy.nextAvailable(RuleDecisionSnapshot(scheduleBlocked = true), now, schedules)
        assertEquals(now.withHour(14), available)
        assertTrue(ScheduleEvaluator.evaluateAll(schedules, checkNotNull(available)).allowed)
    }

    @Test fun disjointSchedulesNeverPromiseAvailability() {
        val schedules = dailyWindow(11 * 60, 12 * 60) + dailyWindow(14 * 60, 18 * 60)
        assertNull(RuleAvailabilityPolicy.nextAvailable(RuleDecisionSnapshot(scheduleBlocked = true), now, schedules))
    }

    @Test fun planOnlyDoesNotRecordQuotaHit() {
        assertFalse(com.liuml.apptimelimiter.nonroot.NonRootLimitHitPolicy.shouldRecord(
            com.liuml.apptimelimiter.nonroot.NonRootBlockReason.SESSION_PLAN,
            quotaIncidentIsNew = true, scheduleIncidentIsNew = true,
        ))
    }

    @Test fun dailyAndScheduleMustBothClear() {
        val snapshot = RuleDecisionSnapshot(scheduleBlocked = true, appDailyRemainingMillis = 0)
        assertEquals(now.plusDays(1).withHour(14), RuleAvailabilityPolicy.nextAvailable(snapshot, now, dailyWindow(14 * 60, 18 * 60)))
    }

    @Test fun unknownSessionResetCannotPromiseCooldownEnd() {
        val snapshot = RuleDecisionSnapshot(cooldownRemainingMillis = 60_000, appPerLaunchRemainingMillis = 0)
        assertNull(RuleAvailabilityPolicy.nextAvailable(snapshot, now, emptyList()))
        assertEquals(now.plusMinutes(2), RuleAvailabilityPolicy.nextAvailable(snapshot, now, emptyList(), now.plusMinutes(2)))
    }

    @Test fun dailyResetUsesLocalMidnightAcrossDst() {
        val dst = ZonedDateTime.parse("2026-03-08T00:30:00-05:00[America/New_York]")
        val expected = ZonedDateTime.parse("2026-03-09T00:00:00-04:00[America/New_York]")
        assertEquals(expected, RuleAvailabilityPolicy.nextAvailable(RuleDecisionSnapshot(appDailyRemainingMillis = 0), dst, emptyList()))
    }

    @Test fun unknownScheduleAndOverflowDoNotInventTime() {
        assertNull(RuleAvailabilityPolicy.nextAvailable(RuleDecisionSnapshot(availabilityKnown = false), now, emptyList()))
        assertNull(RuleAvailabilityPolicy.nextAvailable(RuleDecisionSnapshot(scheduleBlocked = true), now, emptyList()))
        assertNull(RuleAvailabilityPolicy.nextAvailable(RuleDecisionSnapshot(cooldownRemainingMillis = Long.MAX_VALUE), now, emptyList()))
    }

    @Test fun bilingualDetailsContainPrimaryAndOtherRestrictions() {
        val snapshot = RuleDecisionSnapshot(cooldownRemainingMillis = 1, groupDailyRemainingMillis = 0, grouped = true)
        assertTrue(RestrictionReasonText.details(snapshot, false).contains("其他限制：分组每日"))
        assertTrue(RestrictionReasonText.details(snapshot, true).contains("Main reason: Cooldown"))
        RestrictionReason.values().forEach {
            assertTrue(RestrictionReasonText.label(it, true).isNotBlank())
            assertTrue(RestrictionReasonText.label(it, false).isNotBlank())
        }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun resultsCannotBeMutatedByConsumers() {
        val snapshot = RuleDecisionSnapshot(appDailyRemainingMillis = 0)
        (snapshot.reasons as MutableList).clear()
    }

    @Test fun detailsShowKnownMeasurementsAndCooldownInBothLanguages() {
        val snapshot = RuleDecisionSnapshot(cooldownRemainingMillis = 61_001L,
            appDailyMeasurement = RuleUsageMeasurement.fromProvider(120_000L, 600L, 1L, 3600L))
        val chinese = RestrictionReasonText.details(snapshot, false)
        val english = RestrictionReasonText.details(snapshot, true)
        assertTrue(chinese.contains("已用 2分0秒 / 额度 10分0秒"))
        assertTrue(chinese.contains("剩余冷却：1分2秒"))
        assertTrue(english.contains("used 2m 0s / allowance 10m 0s"))
        assertTrue(english.contains("Cooldown remaining: 1m 2s"))
        assertEquals(LimitBlockReason.COOLDOWN, snapshot.gate.blockingReason)
    }

    @Test fun unknownUsageIsNotRenderedAsZeroAndInvalidLimitIsOmitted() {
        val knownLimit = RuleUsageMeasurement.fromProvider(-1L, 600L, 1L, 3600L)
        val text = RestrictionReasonText.details(RuleDecisionSnapshot(appDailyMeasurement = knownLimit), true)
        assertFalse(text.contains("used "))
        assertTrue(text.contains("allowance 10m 0s"))
        assertNull(RuleUsageMeasurement.fromProvider(-1L, Long.MAX_VALUE, 1L, 3600L))
        assertNull(RuleUsageMeasurement.fromProvider(120_000L, 0L, 1L, 3600L)?.limitMillis)
    }

    @Test fun groupDetailsNeverDisplayPausedPersonalMeasurements() {
        val snapshot = RuleDecisionSnapshot(grouped = true,
            appDailyMeasurement = RuleUsageMeasurement(600_000L, 600_000L),
            groupDailyMeasurement = RuleUsageMeasurement(120_000L, 900_000L))
        val text = RestrictionReasonText.details(snapshot, true)
        assertFalse(text.contains("App daily"))
        assertTrue(text.contains("Group daily"))
        assertTrue(text.contains("used 2m 0s / allowance 15m 0s"))
        assertNull(snapshot.primaryReason)
    }
}
