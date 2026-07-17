package com.liuml.apptimelimiter.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsRepository(context: Context) {
    private val logFile = File(context.applicationContext.filesDir, FILE_NAME)

    fun append(level: String, packageName: String, event: String, message: String) {
        synchronized(FILE_LOCK) {
            rotateIfNeeded()
            val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
            val cleanMessage = message.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
            logFile.appendText("$timestamp\t$level\t$packageName\t$event\t$cleanMessage\n")
        }
    }

    fun readLatest(limit: Int = 200): List<String> = synchronized(FILE_LOCK) {
        if (!logFile.exists()) emptyList()
        else logFile.readLines().takeLast(limit).asReversed()
    }

    fun clear() {
        synchronized(FILE_LOCK) {
            if (logFile.exists()) logFile.writeText("")
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_FILE_BYTES) return
        val retained = logFile.readLines().takeLast(RETAINED_LINES)
        logFile.writeText(retained.joinToString(separator = "\n", postfix = "\n"))
    }

    private companion object {
        const val FILE_NAME = "diagnostics.log"
        const val MAX_FILE_BYTES = 256L * 1024L
        const val RETAINED_LINES = 500
        val FILE_LOCK = Any()
    }
}
