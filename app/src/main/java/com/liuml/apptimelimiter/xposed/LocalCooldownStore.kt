package com.liuml.apptimelimiter.xposed

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import com.liuml.apptimelimiter.core.CooldownClock
import com.liuml.apptimelimiter.core.SharedCooldownClaim
import com.liuml.apptimelimiter.core.SharedCooldownClaimStatus
import com.liuml.apptimelimiter.core.SharedCooldownPolicy
import com.liuml.apptimelimiter.core.SharedCooldownRecord
import org.json.JSONObject
import java.io.File

/** Target-private only: SQLite's file locks serialize processes, never different app UIDs.
 * No cached successful state: even a failed commit/endTransaction propagates to the Hook gate.
 * Old preferences are imported once, in the same transaction as the first operation. Old Hook
 * processes must be restarted on upgrade; concurrent legacy writers cannot join this protocol.
 * IDs and ended snapshot tombstones have a 31-day retention window. A durable nondecreasing
 * reference-time floor rejects older input after collection. Its clock advances only with
 * verified same-boot elapsed time; wall-clock discontinuities suspend collection.
 * Capacity failure is limited to the retained window, not the database's lifetime.
 */
internal class LocalCooldownStore(
    private val context: Context,
    private val file: File = File(context.noBackupFilesDir, "__app_time_limiter_cooldown.sqlite"),
    private val prefsName: String = "__app_time_limiter_state__",
    private val clock: () -> Time = {
        Time(System.currentTimeMillis(), SystemClock.elapsedRealtime(), CooldownClock.bootCount(context))
    },
) {
    data class Time(val wall: Long, val elapsed: Long, val boot: Int)

    fun read(identity: String, duration: Long): SharedCooldownRecord = transaction { db, now ->
        migrate(db, identity, duration)
        restore(db, "local", identity, now)
    }

    fun claim(identity: String, duration: Long, incident: String, source: String,
        occurredAt: Long): SharedCooldownClaim = claimInternal(identity, duration, incident, source, occurredAt, null)

    /** Hook captures this evidence once per incident, before any retry or wall-clock change. */
    fun claimWithClock(identity: String, duration: Long, incident: String, source: String,
        occurred: Time): SharedCooldownClaim =
        claimInternal(identity, duration, incident, source, occurred.wall, occurred)

    private fun claimInternal(identity: String, duration: Long, incident: String, source: String,
        occurredAt: Long, evidence: Time?): SharedCooldownClaim = transaction { db, now ->
        migrate(db, identity, duration)
        check(duration <= 0 || now.boot >= 0) { "Cooldown boot identity unavailable" }
        val existing = restore(db, "local", identity, now)
        // Validate before allowing an event to affect retention. Never clamp a stale event
        // into the current window: that would turn a collected retry into a new incident.
        val eventTime = if (evidence != null) {
            require(evidence.boot >= 0 && evidence.boot == now.boot &&
                evidence.elapsed >= 0 && evidence.elapsed <= now.elapsed) { "Invalid cooldown event clock" }
            (referenceTime(db) - (now.elapsed - evidence.elapsed)).coerceAtLeast(0L)
        } else {
            require(occurredAt >= 0 && occurredAt <= now.wall) { "Invalid cooldown event time" }
            // Compatibility callers have no evidence to disambiguate wall-clock eras.
            // Never reinterpret their stale timestamps as fresh events during a jump.
            occurredAt
        }
        if (eventTime < floor(db)) return@transaction SharedCooldownClaim(
            SharedCooldownClaimStatus.ALREADY_HANDLED, existing, emptyList(),
        )
        val handled = db.rawQuery("SELECT id FROM handled WHERE id = ?", arrayOf(incident)).use {
            if (it.moveToFirst()) listOf(incident) else emptyList()
        }
        // Legacy callers can retry a retained ID, but cannot insert a wall-only timestamp
        // from a different clock era. Production Hook calls always carry elapsed evidence.
        check(evidence != null || handled.isNotEmpty() ||
            kotlin.math.abs(now.wall - referenceTime(db)) <= CLOCK_TOLERANCE_MILLIS) {
            "Cooldown event requires monotonic clock evidence"
        }
        val effectiveOccurredAt = if (evidence != null) {
            (now.wall - (now.elapsed - evidence.elapsed)).coerceAtLeast(0L)
        } else occurredAt
        val claim = SharedCooldownPolicy.claim(existing, handled, incident, source,
            effectiveOccurredAt, duration, now.wall, now.elapsed, now.boot)
        if (claim.isNewIncident) {
            // Only the live 31-day window is capped. Transaction maintenance recovers capacity.
            val count = db.rawQuery("SELECT count(*) FROM handled", null).use { it.moveToFirst(); it.getLong(0) }
            check(count < MAX_INCIDENTS) { "Cooldown 31-day incident window full" }
            db.insertOrThrow("handled", null, ContentValues().apply {
                put("id", incident); put("occurred_at", eventTime)
            })
        }
        save(db, "local", identity, claim.record)
        claim
    }

    fun readGroup(identity: String, seed: SharedCooldownRecord): SharedCooldownRecord = transaction { db, now ->
        val stored = load(db, "group", identity)
        // Collected snapshots cannot be rebased again, even after a clock rollback/reboot.
        val snapshotFloor = db.rawQuery("SELECT snapshot_floor FROM retention WHERE id=1", null).use {
            it.moveToFirst(); it.getLong(0)
        }
        val currentBootActive = seed.bootCount == now.boot && now.boot >= 0 && seed.endsAtElapsedMillis > now.elapsed
        if (stored == null && !currentBootActive && seed.endsAtMillis < snapshotFloor) return@transaction SharedCooldownRecord()
        val legacy = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        CooldownClock.requireHealthy(legacy)
        // A current-boot deadline supplied by Provider/mirror is already authoritative;
        // only wall-only legacy snapshots need the target-private rebase cache.
        val initial = if (stored != null && stored.endsAtMillis == 0L) {
            stored // Durable ended tombstone, including across boots.
        } else if (seed.bootCount == now.boot && now.boot >= 0 && seed.endsAtElapsedMillis > 0) {
            seed
        } else stored ?: if (legacy.getString("group_cooldown_rebase_identity", null) == identity) {
            seed.copy(
                startedAtElapsedMillis = legacy.getLong("group_cooldown_rebase_start_elapsed", -1),
                endsAtElapsedMillis = legacy.getLong("group_cooldown_rebase_end_elapsed", -1),
                bootCount = legacy.getInt("group_cooldown_rebase_boot", -1),
            )
        } else seed
        val restored = SharedCooldownPolicy.rebase(initial, now.wall, now.elapsed, now.boot)
        save(db, "group", identity, restored)
        restored
    }

    private fun migrate(db: SQLiteDatabase, identity: String, duration: Long) {
        if (db.rawQuery("SELECT 1 FROM metadata WHERE id = 1", null).use { it.moveToFirst() }) return
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        CooldownClock.requireHealthy(prefs)
        val start = prefs.getLong("cooldown_started_at", 0)
        val end = prefs.getLong("cooldown_ends_at", 0).takeIf { it > 0 }
            ?: if (start > 0) start + duration.coerceIn(0, Long.MAX_VALUE - start) else 0
        val oldIdentity = prefs.getString("cooldown_rule_identity", null)
            ?: identity.takeIf { it.startsWith("app:${prefs.getLong("cooldown_rule_version", -1)}:") }
            // Very old snapshots may have no rule identity. Preserve an already effective
            // deadline conservatively instead of treating the upgrade as a fresh allowance.
            ?: identity.takeIf { end > 0 && !prefs.contains("cooldown_rule_version") }
        if (oldIdentity != null) save(db, "local", oldIdentity, SharedCooldownRecord(
            startedAtMillis = start, endsAtMillis = end,
            incidentId = prefs.getString("cooldown_incident_id", null).orEmpty(),
            sourcePackage = context.packageName,
            startedAtElapsedMillis = prefs.getLong("cooldown_started_elapsed_at", 0),
            endsAtElapsedMillis = prefs.getLong("cooldown_ends_elapsed_at", 0),
            bootCount = prefs.getInt("cooldown_boot_count", -1),
        ))
        val handled = (prefs.getString("handled_quota_incidents", null).orEmpty().lineSequence() +
            sequenceOf(prefs.getString("cooldown_incident_id", null).orEmpty()))
            .filter(String::isNotBlank).distinct().toList()
        check(handled.size <= MAX_INCIDENTS) { "Legacy cooldown ledger full" }
        // Unknown legacy event times receive one full retention window, never a fresh window
        // on each read. The migration marker and rows commit together.
        handled.forEach { db.insertOrThrow("handled", null, ContentValues().apply {
            put("id", it); put("occurred_at", referenceTime(db))
        }) }
        db.execSQL("INSERT INTO metadata(id) VALUES(1)")
    }

    private fun restore(db: SQLiteDatabase, slot: String, identity: String, now: Time): SharedCooldownRecord {
        val record = load(db, slot, identity) ?: return SharedCooldownRecord()
        return SharedCooldownPolicy.rebase(record, now.wall, now.elapsed, now.boot).also {
            save(db, slot, identity, it)
        }
    }

    private fun load(db: SQLiteDatabase, slot: String, identity: String): SharedCooldownRecord? =
        db.rawQuery("SELECT record, expired FROM cooldown WHERE slot = ? AND identity = ?", arrayOf(slot, identity)).use {
            if (!it.moveToFirst()) return@use null
            if (it.getInt(1) != 0) return@use SharedCooldownRecord()
            val json = JSONObject(it.getString(0))
            SharedCooldownRecord(json.getLong("start"), json.getLong("end"), json.getString("incident"),
                json.getString("source"), json.getLong("elapsedStart"), json.getLong("elapsedEnd"), json.getInt("boot"))
        }

    private fun save(db: SQLiteDatabase, slot: String, identity: String, record: SharedCooldownRecord) {
        val now = clock()
        val previous = db.rawQuery("SELECT record, retain_at, snapshot_end FROM cooldown WHERE slot = ? AND identity = ?",
            arrayOf(slot, identity)).use { cursor ->
            if (cursor.moveToFirst()) Triple(JSONObject(cursor.getString(0)), cursor.getLong(1), cursor.getLong(2)) else null
        }
        // Reads/rebases must not renew retention. A genuinely new deadline gets a new window.
        val retainAt = if (previous != null && (record.endsAtMillis == 0L ||
            (previous.first.getLong("end") == record.endsAtMillis &&
                previous.first.getString("incident") == record.incidentId))) previous.second
            else referenceTime(db) + SharedCooldownPolicy.remainingMillisDual(record, now.wall, now.elapsed, now.boot)
        if (load(db, slot, identity) == null) {
            val count = db.rawQuery("SELECT count(*) FROM cooldown", null).use { it.moveToFirst(); it.getLong(0) }
            check(count < MAX_INCIDENTS) { "Cooldown retained snapshot window full" }
        }
        val json = JSONObject().put("start", record.startedAtMillis).put("end", record.endsAtMillis)
            .put("incident", record.incidentId).put("source", record.sourcePackage)
            .put("elapsedStart", record.startedAtElapsedMillis).put("elapsedEnd", record.endsAtElapsedMillis)
            .put("boot", record.bootCount)
        db.insertWithOnConflict("cooldown", null, ContentValues().apply {
            put("slot", slot); put("identity", identity); put("record", json.toString())
            put("retain_at", retainAt)
            put("snapshot_end", maxOf(previous?.third ?: 0L, record.endsAtMillis))
            put("expired", if (record.endsAtMillis <= 0L || (now.boot >= 0 &&
                SharedCooldownPolicy.remainingMillisDual(record, now.wall, now.elapsed, now.boot) == 0L)) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Cooldown write failed" } }
    }

    private fun <T> transaction(action: (SQLiteDatabase, Time) -> T): T {
        // Open independent connections; BEGIN EXCLUSIVE acquires the cross-process writer lock
        // BEFORE reading either the migration marker or the existing deadline.
        return SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS metadata(id INTEGER PRIMARY KEY)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cooldown(slot TEXT NOT NULL, identity TEXT NOT NULL, record TEXT NOT NULL, PRIMARY KEY(slot, identity))")
                db.execSQL("CREATE TABLE IF NOT EXISTS handled(id TEXT PRIMARY KEY)")
                val now = clock()
                maintainWindow(db, now)
                val result = action(db, now)
                db.setTransactionSuccessful()
                result
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun floor(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT floor FROM retention WHERE id = 1", null).use { it.moveToFirst(); it.getLong(0) }

    private fun referenceTime(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT reference_wall FROM retention WHERE id = 1", null).use { it.moveToFirst(); it.getLong(0) }

    private fun maintainWindow(db: SQLiteDatabase, now: Time) {
        require(now.wall >= 0)
        if (db.version < 2) {
            db.execSQL("ALTER TABLE handled ADD COLUMN occurred_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE cooldown ADD COLUMN retain_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE cooldown ADD COLUMN expired INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE handled SET occurred_at = ?", arrayOf(now.wall))
            db.execSQL("UPDATE cooldown SET retain_at = ?", arrayOf(now.wall))
            // Old records can end after upgrade time. Their tombstones must outlive the
            // original snapshot end, otherwise rollback could admit that snapshot again.
            db.rawQuery("SELECT slot, identity, record FROM cooldown", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val end = JSONObject(cursor.getString(2)).getLong("end")
                    db.execSQL("UPDATE cooldown SET retain_at = ? WHERE slot = ? AND identity = ?",
                        arrayOf(maxOf(now.wall, end), cursor.getString(0), cursor.getString(1)))
                }
            }
            db.version = 2
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS retention(id INTEGER PRIMARY KEY, floor INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO retention(id, floor) VALUES(1, 0)")
        if (db.version < 3) {
            db.execSQL("ALTER TABLE retention ADD COLUMN reference_wall INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE retention ADD COLUMN reference_elapsed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE retention ADD COLUMN reference_boot INTEGER NOT NULL DEFAULT -1")
            // Preserve a v2 deletion fence; new evidenced events use this logical epoch even
            // if an older build had already poisoned that fence with a wall-clock jump.
            val oldFloor = floor(db)
            val reference = if (oldFloor == 0L) now.wall else
                maxOf(now.wall, oldFloor + WINDOW_MILLIS.coerceAtMost(Long.MAX_VALUE - oldFloor))
            db.execSQL("UPDATE retention SET reference_wall=?,reference_elapsed=?,reference_boot=? WHERE id=1",
                arrayOf(reference, now.elapsed, now.boot))
            db.version = 3
        }
        if (db.version < 4) {
            db.execSQL("ALTER TABLE retention ADD COLUMN observed_wall INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE retention ADD COLUMN snapshot_floor INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE cooldown ADD COLUMN snapshot_end INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE retention SET observed_wall=?,snapshot_floor=floor WHERE id=1", arrayOf(now.wall))
            db.rawQuery("SELECT slot,identity,record FROM cooldown", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val json = JSONObject(cursor.getString(2))
                    // Reset unknown old retention to one full logical window, once only.
                    db.execSQL("UPDATE cooldown SET retain_at=?,snapshot_end=? WHERE slot=? AND identity=?",
                        arrayOf(referenceTime(db) + (json.getLong("end") - json.getLong("start")).coerceAtLeast(0L),
                            json.getLong("end"), cursor.getString(0), cursor.getString(1)))
                }
            }
            db.version = 4
        }
        val anchor = db.rawQuery("SELECT reference_wall,reference_elapsed,reference_boot FROM retention WHERE id=1", null).use {
            it.moveToFirst(); Time(it.getLong(0), it.getLong(1), it.getInt(2))
        }
        val sameBoot = now.boot >= 0 && anchor.boot == now.boot && now.elapsed >= anchor.elapsed
        val delta = if (sameBoot) now.elapsed - anchor.elapsed else 0L
        val reference = anchor.wall + delta.coerceAtMost(Long.MAX_VALUE - anchor.wall)
        val observedWall = db.rawQuery("SELECT observed_wall FROM retention WHERE id=1", null).use {
            it.moveToFirst(); it.getLong(0)
        }
        db.execSQL("UPDATE retention SET reference_wall=?,reference_elapsed=?,reference_boot=?,observed_wall=? WHERE id=1",
            arrayOf(reference, now.elapsed, now.boot, now.wall))
        // No offline-time guess across reboot and no deletion on an unverified wall jump.
        // New Hook events still have same-boot elapsed evidence and remain claimable.
        // Re-anchor observation after a discontinuity, but NEVER move reference/floor with it.
        // Stable ticks resume GC even if the user keeps the newly selected wall time.
        if (!sameBoot || kotlin.math.abs((now.wall - observedWall) - delta) > CLOCK_TOLERANCE_MILLIS) return
        val cutoff = maxOf(floor(db), (reference - WINDOW_MILLIS).coerceAtLeast(0L))
        db.execSQL("UPDATE retention SET floor = ? WHERE id = 1", arrayOf(cutoff))
        db.delete("handled", "occurred_at < ?", arrayOf(cutoff.toString()))
        val candidates = db.rawQuery("SELECT slot, identity FROM cooldown WHERE retain_at < ?",
            arrayOf(cutoff.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
        for ((slot, identity) in candidates) {
            val record = load(db, slot, identity) ?: continue
            // A wall-clock jump must not delete a still-active monotonic deadline.
            if (record.endsAtMillis == 0L || (now.boot >= 0 &&
                SharedCooldownPolicy.remainingMillisDual(record, now.wall, now.elapsed, now.boot) == 0L)) {
                val end = db.rawQuery("SELECT snapshot_end FROM cooldown WHERE slot=? AND identity=?",
                    arrayOf(slot, identity)).use { it.moveToFirst(); it.getLong(0) }
                db.execSQL("UPDATE retention SET snapshot_floor=max(snapshot_floor,?) WHERE id=1",
                    arrayOf(if (end == Long.MAX_VALUE) end else end + 1))
                db.delete("cooldown", "slot = ? AND identity = ?", arrayOf(slot, identity))
            }
        }
    }

    companion object {
        private const val MAX_INCIDENTS = 4096
        internal const val WINDOW_MILLIS = 31L * 24 * 60 * 60 * 1000
        private const val CLOCK_TOLERANCE_MILLIS = 5_000L
    }
}
