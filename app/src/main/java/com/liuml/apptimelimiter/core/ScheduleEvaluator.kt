package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.data.ScheduleWindow
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class ScheduleDecision(
    val allowed: Boolean,
    val nextTransition: ZonedDateTime?,
) {
    fun millisUntilTransition(now: ZonedDateTime): Long? = nextTransition
        ?.let { Duration.between(now, it).toMillis().coerceAtLeast(0L) }
}

object ScheduleEvaluator {
    fun evaluate(
        mode: ScheduleMode,
        windows: List<ScheduleWindow>,
        now: ZonedDateTime,
    ): ScheduleDecision {
        val validWindows = windows.filter(ScheduleWindow::isValid)
        val allowedNow = isAllowed(mode, validWindows, now)
        val transition = transitionCandidates(validWindows, now.toLocalDate(), now.zone)
            .asSequence()
            .filter { it.isAfter(now) }
            .distinct()
            .sorted()
            .firstOrNull { candidate ->
                val justAfter = candidate.plusNanos(1_000_000)
                isAllowed(mode, validWindows, justAfter) != allowedNow
            }
        return ScheduleDecision(allowedNow, transition)
    }

    fun isAllowed(
        mode: ScheduleMode,
        windows: List<ScheduleWindow>,
        now: ZonedDateTime,
    ): Boolean {
        val insideWindow = windows.any { contains(it, now) }
        return when (mode) {
            ScheduleMode.ALLOW_ONLY -> insideWindow
            ScheduleMode.BLOCK_DURING -> !insideWindow
        }
    }

    private fun contains(window: ScheduleWindow, now: ZonedDateTime): Boolean {
        if (!window.isValid()) return false
        val day = now.dayOfWeek.value
        val previousDay = if (day == 1) 7 else day - 1
        val minute = now.hour * 60 + now.minute
        return if (!window.crossesMidnight) {
            day in window.daysOfWeek && minute in window.startMinute until window.endMinute
        } else {
            (day in window.daysOfWeek && minute >= window.startMinute) ||
                (previousDay in window.daysOfWeek && minute < window.endMinute)
        }
    }

    private fun transitionCandidates(
        windows: List<ScheduleWindow>,
        today: LocalDate,
        zone: java.time.ZoneId,
    ): List<ZonedDateTime> {
        if (windows.isEmpty()) return emptyList()
        return buildList {
            for (offset in -1L..8L) {
                val date = today.plusDays(offset)
                val day = date.dayOfWeek.value
                windows.filter { day in it.daysOfWeek }.forEach { window ->
                    add(date.atMinute(window.startMinute).atZone(zone))
                    val endDate = if (window.crossesMidnight) date.plusDays(1) else date
                    add(endDate.atMinute(window.endMinute).atZone(zone))
                }
            }
        }
    }

    private fun LocalDate.atMinute(minute: Int) = atTime(
        LocalTime.of(minute / 60, minute % 60),
    )
}
