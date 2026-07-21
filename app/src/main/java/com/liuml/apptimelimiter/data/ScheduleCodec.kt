package com.liuml.apptimelimiter.data

object ScheduleCodec {
    fun encode(windows: List<ScheduleWindow>): String = windows
        .asSequence()
        .filter(ScheduleWindow::isValid)
        .take(MAX_WINDOWS)
        .joinToString(";") { window ->
            val days = window.daysOfWeek.sorted().joinToString(",")
            "$days:${window.startMinute}-${window.endMinute}"
        }

    fun decode(value: String?): List<ScheduleWindow> = value
        .orEmpty()
        .splitToSequence(';')
        .mapNotNull(::decodeWindow)
        .take(MAX_WINDOWS)
        .toList()

    private fun decodeWindow(value: String): ScheduleWindow? {
        if (value.isBlank() || value.length > MAX_ENCODED_WINDOW_LENGTH) return null
        val parts = value.split(':', limit = 2)
        if (parts.size != 2) return null
        val days = parts[0].split(',').mapNotNull(String::toIntOrNull).toSet()
        val times = parts[1].split('-', limit = 2)
        if (times.size != 2) return null
        val window = ScheduleWindow(
            daysOfWeek = days,
            startMinute = times[0].toIntOrNull() ?: return null,
            endMinute = times[1].toIntOrNull() ?: return null,
        )
        return window.takeIf(ScheduleWindow::isValid)
    }

    const val MAX_WINDOWS = 32
    private const val MAX_ENCODED_WINDOW_LENGTH = 64
}
