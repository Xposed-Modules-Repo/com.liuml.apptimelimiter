package com.liuml.apptimelimiter

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** All preferences, including simulated cold starts, remain in isolated audit files. */
class StatisticsLedgerRecoveryInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val stores = mutableListOf<String>()
    private val storage = newStorage()
    private val today = LocalDate.now()
    private fun newStorage(): String = "audit_statistics_${UUID.randomUUID()}".also { stores.add(it) }
    private fun context(name: String): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(name, mode)
    }
    private val isolated get() = context(storage)
    private val prefs get() = base.getSharedPreferences(storage, Context.MODE_PRIVATE)

    // A new repository alone shares Android's preference cache. Copy only the committed XML
    // into an unseen isolated name to force an actual disk parse without killing the runner.
    private fun coldContext(): Context {
        val name = newStorage()
        val folder = File(base.applicationInfo.dataDir, "shared_prefs")
        File(folder, "$storage.xml").copyTo(File(folder, "$name.xml"))
        return context(name)
    }

    @After fun cleanup() { stores.forEach { base.deleteSharedPreferences(it) } }

    private fun hit(repo: UsageStatsRepository, id: String, day: LocalDate = today): Boolean =
        repo.record("app.a", 10L, 1, 1, 0, dayToken = day.toString(), eventId = id)

    private fun pending(id: String, day: LocalDate = today) {
        assertTrue(prefs.edit().putBoolean("provider_outbox_pending", true)
            .putString("provider_outbox_day", day.toString())
            .putString("provider_outbox_package", "app.a")
            .putString("provider_outbox_event_id", id)
            .putLong("provider_outbox_duration", 10L)
            .putInt("provider_outbox_launches", 1)
            .putInt("provider_outbox_limit_hits", 1).commit())
    }

    @Test fun moreThan128EventsSurviveDiskReloadAcrossRetryWindow() {
        val repo = UsageStatsRepository(isolated)
        val oldest = today.minusDays(31)
        assertTrue(hit(repo, "oldest", oldest))
        repeat(160) { assertTrue(hit(repo, "event-$it")) }
        val recovered = UsageStatsRepository(coldContext())
        repeat(160) { assertTrue(hit(recovered, "event-$it")) }
        assertTrue(hit(recovered, "oldest", oldest))
        assertEquals(160, recovered.summaryToday("app.a").limitHitCount)
        assertEquals(1600L, recovered.summaryToday("app.a").durationMillis)
        assertEquals(1, recovered.summaryForDay("app.a", oldest).launchCount)
    }

    @Test fun pendingBeforeApplyRecoversOnceFromDisk() {
        UsageStatsRepository(isolated)
        pending("staged")
        val cold = coldContext()
        val recovered = UsageStatsRepository(cold)
        assertEquals(1, recovered.summaryToday("app.a").limitHitCount)
        assertTrue(hit(UsageStatsRepository(cold), "staged"))
        assertEquals(1, recovered.summaryToday("app.a").limitHitCount)
        assertFalse(cold.getSharedPreferences("ignored", 0).getBoolean("provider_outbox_pending", true))
    }

    @Test fun alreadyAppliedPendingDoesNotIncrementAgain() {
        assertTrue(hit(UsageStatsRepository(isolated), "applied"))
        pending("applied")
        val recovered = UsageStatsRepository(coldContext())
        assertEquals(1, recovered.summaryToday("app.a").limitHitCount)
    }

    @Test fun reserveSurvivesDeathBeforeShowAndAfterShowWithoutInventingConfirmation() {
        val repo = UsageStatsRepository(isolated)
        // Both death boundaries have the same durable state: a reservation with no confirm.
        for (index in 1..2) assertTrue(repo.reserveUsageMilestone("app.a", today, index, "owner", "reserve", 100))
        val cold = coldContext()
        val recovered = UsageStatsRepository(cold)
        for (index in 1..2) {
            assertFalse(recovered.reserveUsageMilestone("app.a", today, index, "owner", "reserve", 60_000))
            assertFalse(recovered.reserveUsageMilestone("app.a", today, index, "new-process", "reserve", 0))
        }
        assertEquals(0, recovered.summaryToday("app.a").reminderCount)
        assertFalse(recovered.claimUsageMilestoneReminder("app.a", today, 1))
        assertFalse(recovered.reserveUsageMilestone("app.a", today, 1, "wrong", "confirm", 0))
        assertTrue(recovered.reserveUsageMilestone("app.a", today, 1, "owner", "confirm", 0))
        assertTrue(UsageStatsRepository(cold).reserveUsageMilestone("app.a", today, 1, "owner", "confirm", 0))
        assertEquals(1, recovered.summaryToday("app.a").reminderCount)
        assertTrue(recovered.reserveUsageMilestone("app.a", today, 2, "owner", "cancel", 0))
        assertFalse(recovered.reserveUsageMilestone("app.a", today, 2, "new-process", "reserve", 90_000))
        assertEquals(2, cold.getSharedPreferences("ignored", 0)
            .getStringSet("reminder_ledger.${today.toEpochDay()}", emptySet())!!.size)
    }

    @Test fun capacityFailsClosedWithoutEvictingAnyValidIdentity() {
        val repo = UsageStatsRepository(isolated)
        assertTrue(hit(repo, "retained"))
        val key = "event_ledger.${today.toEpochDay()}"
        val values = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        repeat(4095) { values.add("synthetic-$it") }
        assertTrue(prefs.edit().putStringSet(key, values).commit())
        val recovered = UsageStatsRepository(coldContext())
        assertFalse(hit(recovered, "new"))
        assertTrue(hit(recovered, "retained"))
        assertEquals(1, recovered.summaryToday("app.a").limitHitCount)
    }

    @Test fun confirmedReminderRestoresFromDiskWithoutNewDisplayOrDoubleCount() {
        val repo = UsageStatsRepository(isolated)
        assertTrue(repo.reserveUsageMilestone("app.a", today, 1, "owner", "reserve", 10))
        assertTrue(repo.reserveUsageMilestone("app.a", today, 1, "owner", "confirm", 20))
        val recovered = UsageStatsRepository(coldContext())
        assertFalse(recovered.reserveUsageMilestone("app.a", today, 1, "new", "reserve", 0))
        assertTrue(recovered.reserveUsageMilestone("app.a", today, 1, "owner", "confirm", 0))
        assertEquals(1, recovered.summaryToday("app.a").reminderCount)
    }

    @Test fun deathDuringConfirmReplaysOutboxAndKeepsReservation() {
        val repo = UsageStatsRepository(isolated)
        assertTrue(repo.reserveUsageMilestone("app.a", today, 1, "owner", "reserve", 10))
        pending("usage_milestone:$today:app.a:1")
        assertTrue(prefs.edit().putLong("provider_outbox_duration", 0)
            .putInt("provider_outbox_launches", 0).putInt("provider_outbox_limit_hits", 0)
            .putInt("provider_outbox_reminders", 1).commit())
        val recovered = UsageStatsRepository(coldContext())
        assertEquals(1, recovered.summaryToday("app.a").reminderCount)
        assertFalse(recovered.reserveUsageMilestone("app.a", today, 1, "new", "reserve", 0))
        assertTrue(recovered.reserveUsageMilestone("app.a", today, 1, "owner", "confirm", 0))
        assertEquals(1, recovered.summaryToday("app.a").reminderCount)
    }

    @Test fun pruningDeletesOnlyExpiredShardsAndPersistsBoundary() {
        UsageStatsRepository(isolated)
        val expired = today.minusDays(32).toEpochDay()
        val retained = today.minusDays(31).toEpochDay()
        assertTrue(prefs.edit().putLong("event_ledger_floor", expired)
            .putStringSet("event_ledger.$expired", setOf("old"))
            .putStringSet("reminder_ledger.$expired", setOf("old"))
            .putStringSet("event_ledger.$retained", setOf("valid"))
            .putStringSet("reminder_ledger.$retained", setOf("valid"))
            .commit())
        val cold = coldContext()
        UsageStatsRepository(cold)
        val stored = cold.getSharedPreferences("ignored", 0)
        assertFalse(stored.contains("event_ledger.$expired"))
        assertFalse(stored.contains("reminder_ledger.$expired"))
        assertTrue(stored.contains("event_ledger.$retained"))
        assertTrue(stored.contains("reminder_ledger.$retained"))
        assertEquals(retained, stored.getLong("event_ledger_floor", 0))
    }

    @Test fun upgradeAcceptsNewEventsAndPreservesKnownLegacyDeduplication() {
        assertTrue(prefs.edit().putLong("$today.app.a.duration_ms", 500L)
            .putInt("$today.app.a.limit_hits", 50)
            .putStringSet("processed_events", setOf("retained", "v2:$today:5:app.a:scoped")).commit())
        val upgraded = UsageStatsRepository(isolated)
        assertTrue(hit(upgraded, "new-after-upgrade"))
        assertTrue(upgraded.recordReminderEvent("app.a", today, "new-warning"))
        assertTrue(upgraded.reserveUsageMilestone("app.a", today, 1, "owner", "reserve", 0))
        assertTrue(upgraded.reserveUsageMilestone("app.a", today, 1, "owner", "confirm", 1))
        val recovered = UsageStatsRepository(coldContext())
        assertTrue(hit(recovered, "new-after-upgrade"))
        assertTrue(hit(recovered, "retained"))
        assertTrue(hit(recovered, "scoped"))
        assertEquals(51, recovered.summaryToday("app.a").limitHitCount)
        assertEquals(510L, recovered.summaryToday("app.a").durationMillis)
        assertEquals(2, recovered.summaryToday("app.a").reminderCount)
        // Deliberate compatibility boundary: an ID evicted BEFORE upgrading is unknowable.
        // Its first post-upgrade retry may count once; subsequent retries must not count.
        repeat(3) { assertTrue(hit(recovered, "previously-evicted")) }
        assertEquals(52, recovered.summaryToday("app.a").limitHitCount)
    }

    @Test fun existingDraftFenceIsRemovedWithoutResettingStatisticsOrLedger() {
        val repo = UsageStatsRepository(isolated)
        assertTrue(hit(repo, "existing"))
        assertTrue(prefs.edit().putLong("event_ledger_legacy_through", today.plusDays(1).toEpochDay()).commit())
        val cold = coldContext()
        val recovered = UsageStatsRepository(cold)
        assertTrue(hit(recovered, "existing"))
        assertTrue(hit(recovered, "new"))
        assertEquals(2, recovered.summaryToday("app.a").limitHitCount)
        assertFalse(cold.getSharedPreferences("ignored", 0).contains("event_ledger_legacy_through"))
    }

    @Test fun legacyReservationBlocksOnlyThatReminderAndCanStillBeConfirmed() {
        assertTrue(prefs.edit().putString("milestone_reservation.app.a.event", "usage_milestone:$today:app.a:1")
            .putString("milestone_reservation.app.a.owner", "old-owner")
            .putLong("milestone_reservation.app.a.until", 30_000).commit())
        val recovered = UsageStatsRepository(coldContext())
        assertFalse(recovered.reserveUsageMilestone("app.a", today, 1, "new-owner", "reserve", 60_000))
        assertFalse(recovered.claimUsageMilestoneReminder("app.a", today, 1))
        assertEquals(0, recovered.summaryToday("app.a").reminderCount)
        assertTrue(recovered.reserveUsageMilestone("app.a", today, 2, "new-owner", "reserve", 60_000))
        repeat(2) { assertTrue(recovered.reserveUsageMilestone("app.a", today, 1, "old-owner", "confirm", 60_000)) }
        assertEquals(1, recovered.summaryToday("app.a").reminderCount)
    }

    @Test fun upgradeRecoversPendingNewEventInsteadOfDiscardingIt() {
        assertTrue(prefs.edit().putLong("$today.app.a.duration_ms", 500L)
            .putInt("$today.app.a.limit_hits", 50)
            .putStringSet("processed_events", setOf("retained")).commit())
        pending("new-pending")
        val recovered = UsageStatsRepository(coldContext())
        assertTrue(hit(recovered, "new-pending"))
        assertEquals(51, recovered.summaryToday("app.a").limitHitCount)
        assertEquals(510L, recovered.summaryToday("app.a").durationMillis)
    }

    @Test fun pruningPersistsFloorSoClockRollbackCannotReacceptDeletedEvents() {
        UsageStatsRepository(isolated)
        val floor = today.minusDays(10).toEpochDay()
        assertTrue(prefs.edit().putLong("event_ledger_floor", floor).commit())
        val recovered = UsageStatsRepository(coldContext())
        assertFalse(hit(recovered, "pruned", today.minusDays(11)))
        assertTrue(hit(recovered, "valid", today.minusDays(10)))
    }

    @Test fun commitFailureCannotBeAcknowledgedThroughAnotherRepository() = failedApply(persistApply = true)

    @Test fun failedApplyRestoresDurableStagedRequest() = failedApply(persistApply = false)

    private fun failedApply(persistApply: Boolean) {
        UsageStatsRepository(isolated)
        val delegate = prefs
        var commits = 0
        val failing = object : SharedPreferences by delegate {
            override fun edit(): SharedPreferences.Editor {
                val editor = delegate.edit()
                return object : SharedPreferences.Editor by editor {
                    // Delegated put methods would return the delegate, bypassing commit.
                    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                        editor.putBoolean(key, value); return this
                    }
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value); return this
                    }
                    override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                        editor.putLong(key, value); return this
                    }
                    override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                        editor.putInt(key, value); return this
                    }
                    override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor {
                        editor.putStringSet(key, value); return this
                    }
                    override fun commit(): Boolean {
                        commits++
                        if (commits == 2 && !persistApply) return false
                        val persisted = editor.commit()
                        return persisted && commits != 2 // Ambiguous outcome after apply.
                    }
                }
            }
        }
        val faultContext = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = failing
        }
        assertFalse(hit(UsageStatsRepository(faultContext), "ambiguous"))
        assertFalse(hit(UsageStatsRepository(faultContext), "ambiguous"))
        assertEquals(2, commits)
        // Actual disk state, opened without the failed cache, recovers without double count.
        val recovered = UsageStatsRepository(coldContext())
        assertTrue(hit(recovered, "ambiguous"))
        assertEquals(1, recovered.summaryToday("app.a").limitHitCount)
    }
}
