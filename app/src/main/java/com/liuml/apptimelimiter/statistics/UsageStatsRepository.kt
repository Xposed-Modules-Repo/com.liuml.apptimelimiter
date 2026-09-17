package com.liuml.apptimelimiter.statistics

import android.content.Context
import android.content.SharedPreferences
import com.liuml.apptimelimiter.core.UsageMilestonePolicy
import java.time.LocalDate

data class AppUsageSummary(
    val packageName: String,
    val durationMillis: Long,
    val launchCount: Int,
    val limitHitCount: Int,
    val lastUsedAtMillis: Long,
    val lastHookEventAtMillis: Long = 0L,
    val hookVersionCode: Int = 0,
    val hookModeGeneration: Long = 0L,
    val reminderCount: Int = 0,
    val parentUnlockCount: Int = 0,
    val extensionCount: Int = 0,
)

class UsageStatsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    // SharedPreferences changes its in-memory map even when commit fails. Never acknowledge
    // that speculative state, including through a newly constructed repository in this process.
    private fun SharedPreferences.Editor.durableCommit(): Boolean {
        if (prefs in FAILED_STORES) return false
        val success = runCatching { commit() }.getOrDefault(false)
        if (!success) FAILED_STORES.add(prefs)
        return success
    }

    private fun prepareLedger(): Boolean {
        if (prefs in FAILED_STORES) return false
        val today = LocalDate.now()
        val floor = StatisticsLedgerPolicy.floor(prefs.getLong(KEY_FLOOR, Long.MIN_VALUE), today)
        val editor = prefs.edit()
        var changed = false
        if (!prefs.getBoolean(KEY_LEDGER_READY, false)) {
            editor.putBoolean(KEY_LEDGER_READY, true)
            changed = true
        }
        // Keep the old bounded set as an immutable compatibility shard. Missing/evicted IDs
        // cannot be distinguished from genuinely new requests: accept them and deduplicate
        // subsequent retries in the durable ledger. Never freeze an entire upgrade day.
        if (prefs.contains(KEY_LEGACY_THROUGH)) {
            editor.remove(KEY_LEGACY_THROUGH) // Also repair an already-initialized draft store.
            changed = true
        }
        if (prefs.contains("processed_events") && !prefs.contains(KEY_LEGACY_LAST_DAY)) {
            editor.putLong(KEY_LEGACY_LAST_DAY, today.plusDays(1).toEpochDay())
            changed = true
        }
        if (prefs.contains(KEY_LEGACY_LAST_DAY) && floor > prefs.getLong(KEY_LEGACY_LAST_DAY, Long.MAX_VALUE)) {
            editor.remove("processed_events").remove(KEY_LEGACY_LAST_DAY)
            changed = true
        }
        if (floor != prefs.getLong(KEY_FLOOR, Long.MIN_VALUE)) {
            editor.putLong(KEY_FLOOR, floor)
            prefs.all.keys.filter { it.startsWith(EVENTS_PREFIX) || it.startsWith(RESERVATIONS_PREFIX) }
                .forEach { key ->
                    val epoch = key.substringAfterLast('.').toLongOrNull()
                    if (epoch != null && epoch < floor) editor.remove(key)
                }
            changed = true
        }
        return !changed || editor.durableCommit()
    }

    private fun acceptsNew(day: String): Boolean = StatisticsLedgerPolicy.accepts(
        LocalDate.parse(day), LocalDate.now(), prefs.getLong(KEY_FLOOR, Long.MIN_VALUE),
    )

    private fun eventsKey(day: String) = EVENTS_PREFIX + LocalDate.parse(day).toEpochDay()
    private fun reservationsKey(day: String) = RESERVATIONS_PREFIX + LocalDate.parse(day).toEpochDay()

    init {
        recoverPendingWrite()
    }

    internal fun exportMigrationSnapshot(): Map<String, *> = rawMigrationStorageSnapshot()
        .filterKeys { key ->
            !key.startsWith("heartbeat.") &&
                !key.startsWith("hook_version.") &&
                !key.startsWith("hook_mode_generation.")
        }
        .toMap()

    internal fun rawMigrationStorageSnapshot(): Map<String, *> = synchronized(LOCK) {
        check(recoverPendingWrite()) { "Statistics storage is unavailable" }
        prefs.all.toMap()
    }

    internal fun importMigrationSnapshot(values: Map<String, *>): Boolean = synchronized(LOCK) {
        if (prefs in FAILED_STORES) return@synchronized false
        val editor = prefs.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.durableCommit()
    }

    fun record(
        packageName: String,
        durationMillis: Long,
        launchIncrement: Int,
        limitHitIncrement: Int,
        hookVersionCode: Int,
        hookModeGeneration: Long = 0L,
        dayToken: String? = null,
        eventId: String? = null,
        reminderIncrement: Int = 0,
        parentUnlockIncrement: Int = 0,
        extensionIncrement: Int = 0,
        requireNewEvent: Boolean = false,
    ): Boolean {
        if (packageName.isBlank() || packageName.length > 255) return false
        val day = normalizedUsageDayToken(dayToken) ?: return false
        if (eventId != null && !StatisticsEventIdentity.isValid(eventId)) return false
        return synchronized(LOCK) {
            if (!recoverPendingWrite()) return@synchronized false
            val normalizedEventId = eventId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 160 }
            if (normalizedEventId != null && isProcessed(day, packageName, normalizedEventId)) {
                return@synchronized !requireNewEvent
            }
            if (!acceptsNew(day)) return@synchronized false
            if (normalizedEventId != null && !StatisticsLedgerPolicy.canInsert(
                    prefs.getStringSet(eventsKey(day), emptySet()).orEmpty().size, false,
                    StatisticsLedgerPolicy.MAX_EVENTS_PER_DAY)) return@synchronized false
            // Stage the complete provider request first. If this process is killed after the
            // stage commit, the next provider instance replays it before serving new requests.
            if (!stagePendingWrite(day, packageName, durationMillis, launchIncrement, limitHitIncrement,
                    hookVersionCode, hookModeGeneration, normalizedEventId, reminderIncrement,
                    parentUnlockIncrement, extensionIncrement)) return@synchronized false
            applyRecord(
                packageName = packageName,
                durationMillis = durationMillis,
                launchIncrement = launchIncrement,
                limitHitIncrement = limitHitIncrement,
                hookVersionCode = hookVersionCode,
                hookModeGeneration = hookModeGeneration,
                day = day,
                normalizedEventId = normalizedEventId,
                reminderIncrement = reminderIncrement,
                parentUnlockIncrement = parentUnlockIncrement,
                extensionIncrement = extensionIncrement,
                clearPending = true,
            )
        }
    }

    private fun applyRecord(
        packageName: String,
        durationMillis: Long,
        launchIncrement: Int,
        limitHitIncrement: Int,
        hookVersionCode: Int,
        hookModeGeneration: Long,
        day: String,
        normalizedEventId: String?,
        reminderIncrement: Int = 0,
        parentUnlockIncrement: Int = 0,
        extensionIncrement: Int = 0,
        clearPending: Boolean,
    ): Boolean {
            val processedEventsKey = eventsKey(day)
            val processedEvents = prefs.getStringSet(processedEventsKey, emptySet()).orEmpty().toMutableSet()
            if (normalizedEventId != null && isProcessed(day, packageName, normalizedEventId)) {
                return !clearPending || prefs.edit().putBoolean(KEY_PENDING, false).durableCommit()
            }
            if (!acceptsNew(day)) {
                // An expired pending increment must never be replayed.
                return clearPending && prefs.edit().putBoolean(KEY_PENDING, false).durableCommit()
            }
            if (normalizedEventId != null && processedEvents.size >= StatisticsLedgerPolicy.MAX_EVENTS_PER_DAY) return false
            val prefix = "$day.$packageName."
            // ContentProvider calls can cold-start this process for a single short write.
            // Commit synchronously so Android cannot kill the process before apply() flushes it.
            val editor = prefs.edit()
                .putLong(
                    "${prefix}duration_ms",
                    safeAdd(
                        prefs.getLong("${prefix}duration_ms", 0L).coerceAtLeast(0L),
                        durationMillis.coerceAtLeast(0L),
                    ),
                )
                .putInt(
                    "${prefix}launches",
                    safeAdd(
                        prefs.getInt("${prefix}launches", 0).coerceAtLeast(0),
                        launchIncrement.coerceAtLeast(0),
                    ),
                )
                .putInt(
                    "${prefix}limit_hits",
                    safeAdd(
                        prefs.getInt("${prefix}limit_hits", 0).coerceAtLeast(0),
                        limitHitIncrement.coerceAtLeast(0),
                    ),
                )
                .putInt(
                    "${prefix}reminders",
                    safeAdd(prefs.getInt("${prefix}reminders", 0).coerceAtLeast(0), reminderIncrement.coerceAtLeast(0)),
                )
                .putInt(
                    "${prefix}parent_unlocks",
                    safeAdd(prefs.getInt("${prefix}parent_unlocks", 0).coerceAtLeast(0), parentUnlockIncrement.coerceAtLeast(0)),
                )
                .putInt(
                    "${prefix}extensions",
                    safeAdd(prefs.getInt("${prefix}extensions", 0).coerceAtLeast(0), extensionIncrement.coerceAtLeast(0)),
                )
                .putLong("${prefix}last_used_at", System.currentTimeMillis())
            if (hookVersionCode > 0) {
                editor
                    .putLong("heartbeat.$packageName", System.currentTimeMillis())
                    .putInt("hook_version.$packageName", hookVersionCode)
                    .putLong(
                        "hook_mode_generation.$packageName",
                        hookModeGeneration.coerceAtLeast(0L),
                    )
            }
            if (normalizedEventId != null) {
                processedEvents += StatisticsLedgerPolicy.identity(day, packageName, normalizedEventId)
                editor.putStringSet(processedEventsKey, processedEvents)
            }
            if (clearPending) editor.putBoolean(KEY_PENDING, false)
            return editor.durableCommit()
    }

    private fun stagePendingWrite(
        day: String,
        packageName: String,
        durationMillis: Long,
        launchIncrement: Int,
        limitHitIncrement: Int,
        hookVersionCode: Int,
        hookModeGeneration: Long,
        eventId: String?,
        reminderIncrement: Int,
        parentUnlockIncrement: Int,
        extensionIncrement: Int,
    ): Boolean = prefs.edit()
        .putBoolean(KEY_PENDING, true)
        .putString(KEY_PENDING_DAY, day)
        .putString(KEY_PENDING_PACKAGE, packageName)
        .putLong(KEY_PENDING_DURATION, durationMillis.coerceAtLeast(0L))
        .putInt(KEY_PENDING_LAUNCHES, launchIncrement.coerceAtLeast(0))
        .putInt(KEY_PENDING_LIMIT_HITS, limitHitIncrement.coerceAtLeast(0))
        .putInt(KEY_PENDING_REMINDERS, reminderIncrement.coerceAtLeast(0))
        .putInt(KEY_PENDING_PARENT_UNLOCKS, parentUnlockIncrement.coerceAtLeast(0))
        .putInt(KEY_PENDING_EXTENSIONS, extensionIncrement.coerceAtLeast(0))
        .putInt(KEY_PENDING_HOOK_VERSION, hookVersionCode.coerceAtLeast(0))
        .putLong(KEY_PENDING_MODE_GENERATION, hookModeGeneration.coerceAtLeast(0L))
        .putString(KEY_PENDING_EVENT_ID, eventId)
        .durableCommit()

    private fun isProcessed(day: String, packageName: String, eventId: String): Boolean {
        val processed = if (LocalDate.parse(day).toEpochDay() <= prefs.getLong(KEY_LEGACY_LAST_DAY, Long.MIN_VALUE))
            prefs.getStringSet("processed_events", emptySet()).orEmpty() else emptySet()
        // Recognize legacy unscoped records during upgrade without creating any new ones.
        return eventId in processed || StatisticsEventIdentity.scoped(day, packageName, eventId) in processed ||
            StatisticsLedgerPolicy.identity(day, packageName, eventId) in
            prefs.getStringSet(eventsKey(day), emptySet()).orEmpty()
    }

    private fun legacyReservation(packageName: String, eventId: String): String? {
        val key = "milestone_reservation.$packageName"
        return prefs.getString("$key.owner", null)
            .takeIf { prefs.getString("$key.event", null) == eventId }
    }

    private fun recoverPendingWrite(): Boolean = synchronized(LOCK) {
        if (!prepareLedger()) return@synchronized false
        if (!prefs.getBoolean(KEY_PENDING, false)) return@synchronized true
        val packageName = prefs.getString(KEY_PENDING_PACKAGE, null).orEmpty()
        val day = normalizedUsageDayToken(prefs.getString(KEY_PENDING_DAY, null))
        if (packageName.isBlank() || day == null) {
            return@synchronized prefs.edit().putBoolean(KEY_PENDING, false).durableCommit()
        }
        applyRecord(
            packageName = packageName,
            durationMillis = prefs.getLong(KEY_PENDING_DURATION, 0L),
            launchIncrement = prefs.getInt(KEY_PENDING_LAUNCHES, 0),
            limitHitIncrement = prefs.getInt(KEY_PENDING_LIMIT_HITS, 0),
            hookVersionCode = prefs.getInt(KEY_PENDING_HOOK_VERSION, 0),
            hookModeGeneration = prefs.getLong(KEY_PENDING_MODE_GENERATION, 0L),
            day = day,
            normalizedEventId = prefs.getString(KEY_PENDING_EVENT_ID, null),
            reminderIncrement = prefs.getInt(KEY_PENDING_REMINDERS, 0),
            parentUnlockIncrement = prefs.getInt(KEY_PENDING_PARENT_UNLOCKS, 0),
            extensionIncrement = prefs.getInt(KEY_PENDING_EXTENSIONS, 0),
            clearPending = true,
        )
    }

    fun summaryToday(packageName: String): AppUsageSummary {
        return summaryForDay(packageName, LocalDate.now())
    }

    fun summaryForDay(packageName: String, date: LocalDate): AppUsageSummary = synchronized(LOCK) {
        check(recoverPendingWrite()) { "Statistics storage is unavailable" }
        val prefix = "$date.$packageName."
        AppUsageSummary(
            packageName = packageName,
            durationMillis = prefs.getLong("${prefix}duration_ms", 0L).coerceAtLeast(0L),
            launchCount = prefs.getInt("${prefix}launches", 0).coerceAtLeast(0),
            limitHitCount = prefs.getInt("${prefix}limit_hits", 0).coerceAtLeast(0),
            lastUsedAtMillis = prefs.getLong("${prefix}last_used_at", 0L).coerceAtLeast(0L),
            lastHookEventAtMillis = if (date == LocalDate.now()) prefs.getLong("heartbeat.$packageName", 0L).coerceAtLeast(0L) else 0L,
            hookVersionCode = if (date == LocalDate.now()) prefs.getInt("hook_version.$packageName", 0).coerceAtLeast(0) else 0,
            hookModeGeneration = if (date == LocalDate.now()) prefs.getLong("hook_mode_generation.$packageName", 0L).coerceAtLeast(0L) else 0L,
            reminderCount = prefs.getInt("${prefix}reminders", 0).coerceAtLeast(0),
            parentUnlockCount = prefs.getInt("${prefix}parent_unlocks", 0).coerceAtLeast(0),
            extensionCount = prefs.getInt("${prefix}extensions", 0).coerceAtLeast(0),
        )
    }

    fun summariesToday(packageNames: Collection<String>): List<AppUsageSummary> =
        packageNames.map(::summaryToday)

    fun summariesForDay(packageNames: Collection<String>, date: LocalDate): List<AppUsageSummary> =
        packageNames.map { summaryForDay(it, date) }

    fun summariesBetween(
        startDate: LocalDate,
        endDate: LocalDate,
        packageNames: Collection<String>,
    ): Map<LocalDate, List<AppUsageSummary>> {
        if (packageNames.isEmpty() || endDate.isBefore(startDate)) return emptyMap()
        return generateSequence(startDate) { it.plusDays(1L).takeIf { day -> !day.isAfter(endDate) } }
            .associateWith { date -> summariesForDay(packageNames, date) }
    }

    fun recordReminderEvent(packageName: String, day: LocalDate, eventId: String): Boolean =
        record(packageName, 0L, 0, 0, 0, dayToken = day.toString(), eventId = "reminder:$eventId", reminderIncrement = 1)

    /** At-most-once display attempt: durable reservation survives death, timeout and reboot.
     * Only an explicit confirm counts a display. Cancel leaves a tombstone too: a caller
     * cannot prove that nothing reached the screen before dying or losing its response.
     */
    @Suppress("UNUSED_PARAMETER")
    fun reserveUsageMilestone(packageName: String, day: LocalDate, index: Int,
        owner: String, phase: String, nowElapsed: Long): Boolean = synchronized(LOCK) {
        if (packageName.isBlank() || packageName.length > 255 ||
            !UsageMilestonePolicy.isValidMilestoneIndex(index) || owner.isBlank() || owner.length > 160 ||
            !recoverPendingWrite()) return@synchronized false
        val eventId = UsageMilestonePolicy.eventId(packageName, day.toString(), index)
        val key = reservationsKey(day.toString())
        val reservations = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        val identity = StatisticsLedgerPolicy.identity(day.toString(), packageName, eventId)
        val reservation = "$identity:${StatisticsLedgerPolicy.digest(owner)}"
        when (phase) {
            "reserve" -> {
                if (!acceptsNew(day.toString())) return@synchronized false
                if (isProcessed(day.toString(), packageName, eventId)) return@synchronized false
                if (legacyReservation(packageName, eventId) != null) return@synchronized false
                if (reservations.any { it.startsWith("$identity:") } ||
                    reservations.size >= StatisticsLedgerPolicy.MAX_RESERVATIONS_PER_DAY) return@synchronized false
                reservations.add(reservation)
                prefs.edit().putStringSet(key, reservations).durableCommit()
            }
            "confirm", "cancel" -> {
                if (reservation !in reservations && legacyReservation(packageName, eventId) != owner) return@synchronized false
                if (phase == "cancel") return@synchronized true
                record(packageName, 0, 0, 0, 0,
                    dayToken = day.toString(), eventId = eventId, reminderIncrement = 1)
            }
            else -> false
        }
    }

    fun claimUsageMilestoneReminder(
        packageName: String,
        day: LocalDate,
        milestoneIndex: Int,
    ): Boolean = synchronized(LOCK) {
        if (!UsageMilestonePolicy.isValidMilestoneIndex(milestoneIndex) || !recoverPendingWrite()) return@synchronized false
        val eventId = UsageMilestonePolicy.eventId(packageName, day.toString(), milestoneIndex)
        val identity = StatisticsLedgerPolicy.identity(day.toString(), packageName, eventId)
        if (legacyReservation(packageName, eventId) != null) return@synchronized false
        // Older callers must not bypass a new caller's unconfirmed reservation.
        if (prefs.getStringSet(reservationsKey(day.toString()), emptySet()).orEmpty()
                .any { it.startsWith("$identity:") }) return@synchronized false
        record(
            packageName = packageName,
            durationMillis = 0L,
            launchIncrement = 0,
            limitHitIncrement = 0,
            hookVersionCode = 0,
            dayToken = day.toString(),
            eventId = eventId,
            reminderIncrement = 1,
            requireNewEvent = true,
        )
    }

    fun recordParentUnlockEvent(packageName: String, day: LocalDate, eventId: String): Boolean =
        record(packageName, 0L, 0, 0, 0, dayToken = day.toString(), eventId = "unlock:$eventId", parentUnlockIncrement = 1)

    fun recordExtensionEvent(packageName: String, day: LocalDate, eventId: String): Boolean =
        record(packageName, 0L, 0, 0, 0, dayToken = day.toString(), eventId = "extension:$eventId", extensionIncrement = 1)

    fun totalToday(packageNames: Collection<String>): Long =
        packageNames.fold(0L) { total, packageName ->
            safeAdd(total, summaryToday(packageName).durationMillis)
        }

    fun verifiedHookPackages(
        packageNames: Collection<String>,
        minimumVersionCode: Int,
    ): Set<String> = packageNames.filterTo(mutableSetOf()) { packageName ->
        prefs.getLong("heartbeat.$packageName", 0L) > 0L &&
            prefs.getInt("hook_version.$packageName", 0) >= minimumVersionCode
    }

    fun hookHeartbeatMillis(packageName: String): Long =
        prefs.getLong("heartbeat.$packageName", 0L).coerceAtLeast(0L)

    fun recordHookHeartbeat(
        packageName: String,
        hookVersionCode: Int,
        hookModeGeneration: Long,
    ): Boolean {
        if (packageName.isBlank() || hookVersionCode <= 0) return false
        return synchronized(LOCK) {
            if (!recoverPendingWrite()) return@synchronized false
            prefs.edit()
                .putLong("heartbeat.$packageName", System.currentTimeMillis())
                .putInt("hook_version.$packageName", hookVersionCode)
                .putLong(
                    "hook_mode_generation.$packageName",
                    hookModeGeneration.coerceAtLeast(0L),
                )
                .durableCommit()
        }
    }

    fun clearAll() {
        synchronized(LOCK) {
            check(prefs.edit().clear().durableCommit()) { "Statistics clear failed" }
        }
    }

    private fun dayToken(): String = LocalDate.now().toString()

    private companion object {
        const val PREFS_NAME = "usage_statistics"
        const val EVENTS_PREFIX = "event_ledger."
        const val RESERVATIONS_PREFIX = "reminder_ledger."
        const val KEY_LEDGER_READY = "event_ledger_ready"
        const val KEY_FLOOR = "event_ledger_floor"
        const val KEY_LEGACY_THROUGH = "event_ledger_legacy_through"
        const val KEY_LEGACY_LAST_DAY = "event_ledger_legacy_last_day"
        val FAILED_STORES = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<SharedPreferences, Boolean>(),
        )
        val LOCK = Any()
        const val KEY_PENDING = "provider_outbox_pending"
        const val KEY_PENDING_DAY = "provider_outbox_day"
        const val KEY_PENDING_PACKAGE = "provider_outbox_package"
        const val KEY_PENDING_DURATION = "provider_outbox_duration"
        const val KEY_PENDING_LAUNCHES = "provider_outbox_launches"
        const val KEY_PENDING_LIMIT_HITS = "provider_outbox_limit_hits"
        const val KEY_PENDING_REMINDERS = "provider_outbox_reminders"
        const val KEY_PENDING_PARENT_UNLOCKS = "provider_outbox_parent_unlocks"
        const val KEY_PENDING_EXTENSIONS = "provider_outbox_extensions"
        const val KEY_PENDING_HOOK_VERSION = "provider_outbox_hook_version"
        const val KEY_PENDING_MODE_GENERATION = "provider_outbox_mode_generation"
        const val KEY_PENDING_EVENT_ID = "provider_outbox_event_id"
    }
}

private fun safeAdd(current: Long, increment: Long): Long =
    if (increment > Long.MAX_VALUE - current) Long.MAX_VALUE else current + increment

private fun safeAdd(current: Int, increment: Int): Int =
    if (increment > Int.MAX_VALUE - current) Int.MAX_VALUE else current + increment

internal fun normalizedUsageDayToken(
    value: String?,
    today: LocalDate = LocalDate.now(),
): String? {
    if (value.isNullOrBlank()) return today.toString()
    val parsed = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
    return parsed.toString().takeIf {
        !parsed.isBefore(today.minusDays(MAX_PENDING_DAYS)) &&
            !parsed.isAfter(today.plusDays(MAX_FUTURE_DAYS))
    }
}

private const val MAX_PENDING_DAYS = 31L
private const val MAX_FUTURE_DAYS = 1L
