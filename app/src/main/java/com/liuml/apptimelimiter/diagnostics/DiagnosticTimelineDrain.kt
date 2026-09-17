package com.liuml.apptimelimiter.diagnostics

import android.content.Context
import com.liuml.apptimelimiter.security.ManagerControlDatabase

/** On-demand transfer only: no service or periodic work. Replay uses the durable event key. */
object DiagnosticTimelineDrain {
    private val lock = Any()

    fun drain(context: Context) = synchronized(lock) {
        runCatching {
            val authority = ManagerControlDatabase.get(context)
            val timeline = DiagnosticTimelineRepository(context)
            repeat(9) { // Outbox is bounded to 4096; drain a finite snapshot-sized batch.
                val pending = authority.pendingDiagnostics(500)
                if (pending.isEmpty()) return@runCatching
                if (!timeline.importOutbox(pending)) return@runCatching
                authority.acknowledgeDiagnostics(pending.map { it.id })
            }
        }
        Unit
    }

    fun clear(context: Context) = synchronized(lock) {
        ManagerControlDatabase.get(context).discardDiagnostics()
        DiagnosticTimelineRepository(context).clear()
    }
}
