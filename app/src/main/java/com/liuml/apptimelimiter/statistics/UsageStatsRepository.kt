package com.liuml.apptimelimiter.statistics

import android.content.Context
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

    init {
        recoverPendingWrite()
    }

    internal fun exportMigrationSnapshot(): Map<String, *> = prefs.all
        .filterKeys { key ->
            !key.startsWith("heartbeat.") &&
                !key.startsWith("hook_version.") &&
                !key.startsWith("hook_mode_generation.")
        }
        .toMap()

    internal fun rawMigrationStorageSnapshot(): Map<String, *> = prefs.all.toMap()

    internal fun importMigrationSnapshot(values: Map<String, *>): Boolean {
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
        return editor.commit()
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
    ): Boolean {
        if (packageName.isBlank()) return false
        val day = normalizedUsageDayToken(dayToken) ?: return false
        return synchronized(LOCK) {
            val normalizedEventId = eventId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 160 }
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
            val processedEventsKey = "processed_events"
            val processedEvents = prefs.getStringSet(processedEventsKey, emptySet()).orEmpty().toMutableSet()
            if (normalizedEventId != null && normalizedEventId in processedEvents) {
                if (clearPending) prefs.edit().putBoolean(KEY_PENDING, false).commit()
                return true
            }
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
                processedEvents += normalizedEventId
                while (processedEvents.size > MAX_PROCESSED_EVENTS) {
                    processedEvents.remove(processedEvents.first())
                }
                editor.putStringSet(processedEventsKey, processedEvents)
            }
            if (clearPending) editor.putBoolean(KEY_PENDING, false)
            return editor.commit()
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
        .commit()

    private fun recoverPendingWrite() = synchronized(LOCK) {
        if (!prefs.getBoolean(KEY_PENDING, false)) return@synchronized
        val packageName = prefs.getString(KEY_PENDING_PACKAGE, null).orEmpty()
        val day = normalizedUsageDayToken(prefs.getString(KEY_PENDING_DAY, null))
        if (packageName.isBlank() || day == null) {
            prefs.edit().putBoolean(KEY_PENDING, false).commit()
            return@synchronized
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

    fun summaryForDay(packageName: String, date: LocalDate): AppUsageSummary {
        val prefix = "$date.$packageName."
        return AppUsageSummary(
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

    /** Atomically claims a visible half-hour reminder and records it for statistics. */
    fun claimUsageMilestoneReminder(
        packageName: String,
        day: LocalDate,
        milestoneIndex: Int,
    ): Boolean {
        if (!UsageMilestonePolicy.isValidMilestoneIndex(milestoneIndex)) return false
        return record(
            packageName = packageName,
            durationMillis = 0L,
            launchIncrement = 0,
            limitHitIncrement = 0,
            hookVersionCode = 0,
            dayToken = day.toString(),
            eventId = UsageMilestonePolicy.eventId(packageName, day.toString(), milestoneIndex),
            reminderIncrement = 1,
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
            prefs.edit()
                .putLong("heartbeat.$packageName", System.currentTimeMillis())
                .putInt("hook_version.$packageName", hookVersionCode)
                .putLong(
                    "hook_mode_generation.$packageName",
                    hookModeGeneration.coerceAtLeast(0L),
                )
                .commit()
        }
    }

    fun clearAll() {
        synchronized(LOCK) {
            prefs.edit().clear().commit()
        }
    }

    private fun dayToken(): String = LocalDate.now().toString()

    private companion object {
        const val PREFS_NAME = "usage_statistics"
        const val MAX_PROCESSED_EVENTS = 128
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
