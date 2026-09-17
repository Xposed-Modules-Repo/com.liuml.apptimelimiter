package com.liuml.apptimelimiter.xposed

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.liuml.apptimelimiter.core.SharedCooldownPolicy
import com.liuml.apptimelimiter.core.SharedCooldownRecord
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Independent SQLite connections exercise the actual adapter, not a mock/JVM mutex.
 * A device regression with two target processes is still required for end-to-end Hook coverage.
 */
class LocalCooldownStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "cooldown_test_${UUID.randomUUID()}"
    private val file = File(context.noBackupFilesDir, "$name.sqlite")
    private val prefs get() = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val initial = LocalCooldownStore.Time(10_000, 1_000, 7)
    private fun store(time: LocalCooldownStore.Time = initial) =
        LocalCooldownStore(context, file, name) { time }

    @After fun cleanup() {
        SQLiteDatabase.deleteDatabase(file)
        context.deleteSharedPreferences(name)
    }

    @Test fun simultaneousIndependentConnectionsHaveOneWinnerAndOneDeadline() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map {
                pool.submit(Callable {
                    check(start.await(10, TimeUnit.SECONDS))
                    store().claim("app:1:60000", 60_000, "same", "test.app", 10_000)
                })
            }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isNewIncident })
            assertEquals(1, results.count { it.cooldownStarted })
            assertEquals(setOf(61_000L), results.map { it.record.endsAtElapsedMillis }.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun differentIncidentsAreAbsorbedWithoutExtendingDeadline() {
        val first = store().claim("app:1:60000", 60_000, "first", "test.app", 10_000)
        val second = store(initial.copy(wall = 20_000, elapsed = 11_000))
            .claim("app:1:60000", 60_000, "second", "test.app", 20_000)
        assertTrue(second.isNewIncident)
        assertFalse(second.cooldownStarted)
        assertEquals(first.record, second.record)
        assertFalse(store().claim("app:1:60000", 60_000, "second", "test.app", 10_000).isNewIncident)
    }

    @Test fun migrationPreservesDeadlineAndAllLegacyDedupeThenIgnoresStalePreferences() {
        assertTrue(prefs.edit().putString("cooldown_rule_identity", "app:1:60000")
            .putLong("cooldown_started_at", 5_000).putLong("cooldown_ends_at", 65_000)
            .putLong("cooldown_started_elapsed_at", 500).putLong("cooldown_ends_elapsed_at", 60_500)
            .putInt("cooldown_boot_count", 7).putString("cooldown_incident_id", "first")
            .putString("handled_quota_incidents", "first\nsecond").commit())
        val migrated = store().read("app:1:60000", 60_000)
        assertEquals(65_000, migrated.endsAtMillis)
        assertEquals(60_500, migrated.endsAtElapsedMillis)
        assertFalse(store().claim("app:1:60000", 60_000, "second", "test.app", 10_000).isNewIncident)
        assertTrue(prefs.edit().clear().commit())
        assertEquals(migrated, store().read("app:1:60000", 60_000))
    }

    @Test fun rebootRebaseCommitsOnceAndWallClockChangesCannotShortenIt() {
        store().claim("app:1:60000", 60_000, "first", "test.app", 10_000)
        val reboot = LocalCooldownStore.Time(20_000, 100, 8)
        val restored = store(reboot).read("app:1:60000", 60_000)
        assertEquals(50_100, restored.endsAtElapsedMillis)
        val shifted = reboot.copy(wall = 900_000, elapsed = 200)
        val reread = store(shifted).read("app:1:60000", 60_000)
        assertEquals(restored, reread)
        assertEquals(49_900, SharedCooldownPolicy.remainingMillisDual(reread, shifted.wall, shifted.elapsed, shifted.boot))
    }

    @Test fun oldGroupRebaseSurvivesMigrationAndAlternatingMirrorIdentities() {
        assertTrue(prefs.edit().putString("group_cooldown_rebase_identity", "group-A")
            .putLong("group_cooldown_rebase_start_elapsed", 500)
            .putLong("group_cooldown_rebase_end_elapsed", 40_500)
            .putInt("group_cooldown_rebase_boot", 7).commit())
        val seed = SharedCooldownRecord(startedAtMillis = 5_000, endsAtMillis = 65_000)
        val first = store().readGroup("group-A", seed)
        assertEquals(40_500, first.endsAtElapsedMillis)
        store().readGroup("group-B", seed)
        assertTrue(prefs.edit().clear().commit())
        assertEquals(first, store(initial.copy(wall = 1_000_000)).readGroup("group-A", seed))
    }

    @Test fun failedWriteRollsBackIncidentAndRebaseTogether() {
        store().claim("app:1:60000", 60_000, "first", "test.app", 10_000)
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TRIGGER reject_cooldown AFTER INSERT ON handled BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        }
        val reboot = LocalCooldownStore.Time(20_000, 100, 8)
        // Production carries same-boot elapsed evidence. A wall-only claim after reboot
        // would fail clock validation before reaching the injected SQLite failure.
        val failure = runCatching {
            store(reboot).claimWithClock("app:1:60000", 60_000, "second", "test.app", reboot)
        }.exceptionOrNull()
        assertTrue("Expected the injected SQLite failure, got $failure",
            failure is android.database.sqlite.SQLiteException)
        assertTrue(failure?.message.orEmpty().contains("test failure"))
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.rawQuery("SELECT count(*) FROM handled WHERE id = 'second'", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
            }
            it.rawQuery("SELECT record FROM cooldown WHERE slot = 'local'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7, org.json.JSONObject(cursor.getString(0)).getInt("boot"))
            }
            it.rawQuery("SELECT reference_wall,reference_elapsed,reference_boot FROM retention WHERE id=1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(initial.wall, cursor.getLong(0))
                assertEquals(initial.elapsed, cursor.getLong(1))
                assertEquals(initial.boot, cursor.getInt(2))
            }
            it.execSQL("DROP TRIGGER reject_cooldown")
        }
        val retry = store(reboot).claimWithClock("app:1:60000", 60_000, "second", "test.app", reboot)
        assertTrue(retry.isNewIncident)
        assertFalse(retry.cooldownStarted)
        assertEquals(50_100, retry.record.endsAtElapsedMillis)
        assertEquals(reboot.boot, retry.record.bootCount)
        assertFalse(store(reboot).claimWithClock("app:1:60000", 60_000, "second", "test.app", reboot).isNewIncident)
    }

    @Test fun failedMigrationRollsBackMarkerAndCanRetryWithOriginalDeadline() {
        store().read("empty", 0) // Create schema before injecting a durable failure.
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("DELETE FROM metadata")
            it.execSQL("CREATE TRIGGER reject_migration BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        }
        assertTrue(prefs.edit().putString("cooldown_rule_identity", "app:1:60000")
            .putLong("cooldown_started_at", 5_000).putLong("cooldown_ends_at", 65_000)
            .putString("handled_quota_incidents", "legacy").commit())
        assertTrue(runCatching { store().read("app:1:60000", 60_000) }.isFailure)
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.rawQuery("SELECT count(*) FROM handled", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
            }
            it.execSQL("DROP TRIGGER reject_migration")
        }
        assertEquals(65_000, store().read("app:1:60000", 60_000).endsAtMillis)
        assertFalse(store().claim("app:1:60000", 60_000, "legacy", "test.app", 10_000).isNewIncident)
    }

    @Test fun unknownBootAndUnwritablePathNeverReturnSuccessfulClaim() {
        assertTrue(runCatching {
            store(initial.copy(boot = -1)).claim("app:1:60000", 60_000, "first", "test.app", 10_000)
        }.isFailure)
        assertTrue(runCatching {
            LocalCooldownStore(context, context.noBackupFilesDir, name) { initial }
                .claim("app:1:60000", 60_000, "first", "test.app", 10_000)
        }.isFailure)
        assertTrue(store().claim("app:1:60000", 60_000, "first", "test.app", 10_000).isNewIncident)
    }

    @Test fun fullLedgerRejectsNewClaimsWithoutEvictingOldRetryIdentity() {
        store().claim("app:1:60000", 60_000, "first", "test.app", 10_000)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.beginTransaction()
            try {
                for (index in 1..4095) db.execSQL("INSERT INTO handled(id) VALUES(?)", arrayOf("old-$index"))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        assertTrue(runCatching {
            store().claim("app:1:60000", 60_000, "overflow", "test.app", 10_000)
        }.isFailure)
        assertFalse(store().claim("app:1:60000", 60_000, "first", "test.app", 10_000).isNewIncident)
        assertEquals(61_000L, store().read("app:1:60000", 60_000).endsAtElapsedMillis)
        val later = initial.copy(wall = initial.wall + LocalCooldownStore.WINDOW_MILLIS + 1,
            elapsed = initial.elapsed + LocalCooldownStore.WINDOW_MILLIS + 1)
        val recovered = store(later).claim("app:1:60000", 60_000, "after-window", "test.app", later.wall)
        assertTrue(recovered.isNewIncident)
        assertTrue(recovered.cooldownStarted)
        assertFalse(store(later).claim("app:1:60000", 60_000, "first", "test.app", initial.wall).isNewIncident)
    }

    @Test fun floorIsInclusiveDurableAndNeverRegressesAfterRollbackOrReboot() {
        store().claim("rule", 60_000, "old", "test.app", initial.wall)
        val later = initial.copy(wall = initial.wall + LocalCooldownStore.WINDOW_MILLIS,
            elapsed = initial.elapsed + LocalCooldownStore.WINDOW_MILLIS)
        // Exactly at the floor is retained, not evicted.
        assertFalse(store(later).claim("rule", 60_000, "old", "test.app", initial.wall).isNewIncident)
        val advanced = later.copy(wall = later.wall + 1, elapsed = later.elapsed + 1)
        store(advanced).read("rule", 60_000)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT count(*) FROM handled", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        val rolledBack = initial.copy(boot = 8, elapsed = 100)
        assertFalse(store(rolledBack).claim("rule", 60_000, "old", "test.app", initial.wall).isNewIncident)
        assertFalse(store(rolledBack).claim("rule", 60_000, "unknown-old", "test.app", initial.wall).isNewIncident)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT floor FROM retention", null).use {
                it.moveToFirst(); assertEquals(initial.wall + 1, it.getLong(0))
            }
        }
    }

    @Test fun invalidFutureEventCannotAdvanceFenceOrEvictValidIds() {
        store().claim("rule", 60_000, "old", "test.app", initial.wall)
        assertTrue(runCatching {
            store().claim("rule", 60_000, "future", "test.app", initial.wall + LocalCooldownStore.WINDOW_MILLIS)
        }.isFailure)
        assertFalse(store().claim("rule", 60_000, "old", "test.app", initial.wall).isNewIncident)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT floor FROM retention", null).use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
        }
    }

    @Test fun collectedGroupSnapshotsCannotRebaseAfterClockRollback() {
        val seed = SharedCooldownRecord(startedAtMillis = 5_000, endsAtMillis = 65_000)
        store().readGroup("old-group", seed)
        val expired = initial.copy(wall = 70_000, elapsed = 61_000)
        store(expired).readGroup("old-group", seed) // Persist an ended tombstone.
        val reboot = initial.copy(boot = 8, elapsed = 100)
        assertEquals(0L, store(reboot).readGroup("old-group", seed).endsAtMillis)
        // Neither reboot/wall rollback nor repeated reads prove retention age.
        store(reboot).read("unused", 0)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT count(*) FROM cooldown WHERE slot='group' AND identity='old-group'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            db.rawQuery("SELECT reference_wall,floor FROM retention WHERE id=1", null).use {
                assertTrue(it.moveToFirst()); assertEquals(expired.wall, it.getLong(0)); assertEquals(0L, it.getLong(1))
            }
        }
        // Advance a full window on the NEW boot, with coherent wall/elapsed deltas.
        val later = reboot.copy(wall = reboot.wall + LocalCooldownStore.WINDOW_MILLIS + 1,
            elapsed = reboot.elapsed + LocalCooldownStore.WINDOW_MILLIS + 1)
        store(later).read("unused", 0)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT count(*) FROM cooldown", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT floor,snapshot_floor FROM retention WHERE id=1", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(expired.wall + 1, it.getLong(0))
                assertEquals(seed.endsAtMillis + 1, it.getLong(1))
            }
        }
        assertEquals(0L, store(initial.copy(boot = 9, elapsed = 100)).readGroup("old-group", seed).endsAtMillis)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT count(*) FROM cooldown", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun collectionDoesNotDeleteActiveMonotonicDeadlineOnWallJump() {
        val original = store().claim("rule", 60_000, "active", "test.app", initial.wall).record
        val jump = initial.copy(wall = initial.wall + 2 * LocalCooldownStore.WINDOW_MILLIS, elapsed = 2_000)
        assertEquals(original, store(jump).read("rule", 60_000))
    }

    @Test fun schemaUpgradeRetainsUnknownLegacyTimestampsForOneWindow() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE handled(id TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO handled(id) VALUES('legacy')")
        }
        assertFalse(store().claim("rule", 60_000, "legacy", "test.app", initial.wall).isNewIncident)
        val later = initial.copy(wall = initial.wall + LocalCooldownStore.WINDOW_MILLIS + 1,
            elapsed = initial.elapsed + LocalCooldownStore.WINDOW_MILLIS + 1)
        assertTrue(store(later).claim("rule", 60_000, "fresh", "test.app", later.wall).isNewIncident)
        assertFalse(store(later).claim("rule", 60_000, "legacy", "test.app", initial.wall).isNewIncident)
    }

    @Test fun failedCollectionRollsBackFloorAndRowsTogether() {
        store().claim("rule", 60_000, "old", "test.app", initial.wall)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TRIGGER reject_gc AFTER DELETE ON handled BEGIN SELECT RAISE(ABORT, 'gc failure'); END")
        }
        val later = initial.copy(wall = initial.wall + LocalCooldownStore.WINDOW_MILLIS + 1,
            elapsed = initial.elapsed + LocalCooldownStore.WINDOW_MILLIS + 1)
        assertTrue(runCatching { store(later).read("rule", 60_000) }.isFailure)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT floor FROM retention", null).use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
            db.rawQuery("SELECT count(*) FROM handled", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.execSQL("DROP TRIGGER reject_gc")
        }
        assertTrue(store(later).claim("rule", 60_000, "fresh", "test.app", later.wall).isNewIncident)
    }

    @Test fun fullIdentityLedgerRecoversWithoutRevivingCollectedSnapshots() {
        val seed = SharedCooldownRecord(startedAtMillis = 5_000, endsAtMillis = 65_000)
        store().readGroup("original", seed)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.beginTransaction()
            try {
                for (index in 1..4095) db.execSQL(
                    "INSERT INTO cooldown(slot,identity,record,retain_at,expired,snapshot_end) " +
                        "SELECT slot,?,record,retain_at,expired,snapshot_end FROM cooldown WHERE identity='original'",
                    arrayOf("old-$index"))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        assertTrue(runCatching { store().readGroup("overflow", seed) }.isFailure)
        val later = initial.copy(wall = 70_000 + LocalCooldownStore.WINDOW_MILLIS,
            elapsed = 61_000 + LocalCooldownStore.WINDOW_MILLIS)
        val fresh = seed.copy(startedAtMillis = later.wall, endsAtMillis = later.wall + 60_000)
        assertEquals(fresh.endsAtMillis, store(later).readGroup("fresh", fresh).endsAtMillis)
        assertEquals(0L, store(initial.copy(boot = 8, elapsed = 100)).readGroup("old-1", seed).endsAtMillis)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT count(*) FROM cooldown", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    @Test fun largeWallForwardAndBackAllowsFreshClaimsWithoutChangingActiveDeadline() {
        val first = store().claimWithClock("rule", 60_000, "first", "test.app", initial)
        val forward = initial.copy(wall = initial.wall + 365L * 24 * 60 * 60 * 1000, elapsed = 2_000)
        val second = store(forward).claimWithClock("rule", 60_000, "forward", "test.app", forward)
        assertTrue(second.isNewIncident)
        assertFalse(second.cooldownStarted)
        assertEquals(first.record, second.record)
        val back = initial.copy(wall = 12_000, elapsed = 3_000)
        val third = store(back).claimWithClock("rule", 60_000, "back", "test.app", back)
        assertTrue(third.isNewIncident)
        assertFalse(third.cooldownStarted)
        assertEquals(first.record, third.record)
        // The future-wall event still deduplicates after rollback using its original evidence.
        assertFalse(store(back).claimWithClock("rule", 60_000, "forward", "test.app", forward).isNewIncident)
        assertEquals(first.record, store(back).read("rule", 60_000))
        val afterExpiry = initial.copy(wall = 71_000, elapsed = 62_000)
        assertTrue(store(afterExpiry).claimWithClock("rule", 60_000, "after", "test.app", afterExpiry).cooldownStarted)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT floor FROM retention", null).use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
        }
    }

    @Test fun rejectedOldEventDoesNotClearOrExtendCurrentCooldownAfterClockRollback() {
        store().claimWithClock("rule", 60_000, "old", "test.app", initial)
        val later = initial.copy(wall = initial.wall + LocalCooldownStore.WINDOW_MILLIS + 1,
            elapsed = initial.elapsed + LocalCooldownStore.WINDOW_MILLIS + 1)
        val current = store(later).claimWithClock("rule", 60_000, "current", "test.app", later)
        val back = later.copy(wall = initial.wall, elapsed = later.elapsed + 1)
        val rejected = store(back).claimWithClock("rule", 60_000, "old", "test.app", initial)
        assertFalse(rejected.isNewIncident)
        assertEquals(current.record, rejected.record)
        val fresh = store(back).claimWithClock("rule", 60_000, "fresh-back", "test.app", back)
        assertTrue(fresh.isNewIncident)
        assertFalse(fresh.cooldownStarted)
        assertEquals(current.record, fresh.record)
    }

    @Test fun forwardEraRowsAreCollectedByElapsedAgeEvenWithoutRestoringWallClock() {
        store().read("rule", 60_000)
        val forward = initial.copy(wall = initial.wall + 365L * 24 * 60 * 60 * 1000, elapsed = 2_000)
        store(forward).claimWithClock("rule", 60_000, "forward", "test.app", forward)
        val later = forward.copy(wall = forward.wall + LocalCooldownStore.WINDOW_MILLIS + 1,
            elapsed = forward.elapsed + LocalCooldownStore.WINDOW_MILLIS + 1)
        assertTrue(store(later).claimWithClock("rule", 60_000, "new", "test.app", later).isNewIncident)
        assertFalse(store(later).claimWithClock("rule", 60_000, "forward", "test.app", forward).isNewIncident)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("SELECT count(*) FROM handled", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }
}
