package com.liuml.apptimelimiter

import androidx.test.platform.app.InstrumentationRegistry
import com.liuml.apptimelimiter.diagnostics.DiagnosticTimelinePolicy
import com.liuml.apptimelimiter.diagnostics.DiagnosticTimelineRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DiagnosticTimelineInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "test_timeline_${UUID.randomUUID()}.db"
    private val repository = DiagnosticTimelineRepository(context, name)
    @After fun cleanup() { repository.close(); context.deleteDatabase(name) }

    @Test fun replayIsIdempotentAndDoesNotExposeIncident() {
        repeat(2) { assertTrue(repository.record("com.example.app", "private-incident", "LIMIT_CLAIMED", "OK", eventKey = "outbox-1")) }
        val incident = repository.incidents().single()
        assertEquals(1, incident.eventCount)
        assertNotEquals("private-incident", incident.incident)
        assertEquals("LIMIT_CLAIMED", repository.events(incident.incident).single().stage)
        assertFalse(repository.exportText().contains("private-incident"))
    }

    @Test fun pagesRemainOrderedAndOldEventsAreNotImported() {
        repeat(63) { assertTrue(repository.record("com.example.app", "event", "RESTRICTION_VISIBLE", "OK", eventKey = "entry-$it")) }
        assertTrue(repository.record("com.example.app", "old", "LIMIT_CLAIMED", "OK", eventKey = "old",
            wallMillis = System.currentTimeMillis() - DiagnosticTimelinePolicy.RETENTION_MILLIS - 1000))
        val incident = repository.incidents().single()
        assertEquals(63, incident.eventCount)
        val first = repository.events(incident.incident)
        val second = repository.events(incident.incident, first.last().id)
        assertEquals(50, first.size)
        assertEquals(13, second.size)
        assertTrue(first.last().id < second.first().id)
        repository.clear()
        assertTrue(repository.incidents().isEmpty())
    }

    @Test fun malformedCodesAreRedacted() {
        assertTrue(repository.record("com.example.app", "event", "PIN=1234", "ERROR", "secret with spaces", eventKey = "bad-code"))
        val event = repository.events(repository.incidents().single().incident).single()
        assertEquals("UNKNOWN", event.stage)
        assertEquals("UNKNOWN", event.reason)
    }

    @Test fun capacityIsEnforcedAfterRecoveryFromOversizedHistory() {
        repository.incidents() // Create this test's private schema.
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                repeat(10_010) { index ->
                    db.execSQL("INSERT INTO events(event_key,package,incident,stage,result,reason,wall) VALUES(?,?,?,?,?,?,?)",
                        arrayOf<Any>("seed-$index", "com.example.app", "recovered", "LIMIT_CLAIMED", "OK", "", now))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        assertTrue(repository.record("com.example.app", "new", "LIMIT_CLAIMED", "OK", eventKey = "new"))
        assertEquals(10_000, repository.incidents().sumOf { it.eventCount })
    }

    @Test fun durableReplayAndDirectUiEventsShareTheSameIncident() {
        val pkg = "com.example.app"
        val hash = DiagnosticTimelinePolicy.incidentKey(pkg, "incident")!!
        val pending = listOf(com.liuml.apptimelimiter.security.RuntimeDiagnosticEvent(
            1L, "durable-key", pkg, hash, "LIMIT_CLAIMED", "ACCEPTED", "NONE", System.currentTimeMillis()))
        assertTrue(repository.importOutbox(pending))
        // Simulates death after timeline commit but before outbox acknowledgment.
        assertTrue(repository.importOutbox(pending))
        assertTrue(repository.record(pkg, "incident", "BREAK_PAGE_SHOWN", "INFO", eventKey = "ui-key"))
        assertEquals(2, repository.incidents().single().eventCount)
        assertEquals(2, repository.events(hash).size)
    }

    @Test fun delayedOutboxDeliveryDoesNotReverseEventTime() {
        val now = System.currentTimeMillis()
        assertTrue(repository.record("com.example.app", "incident", "RESTRICTION_VISIBLE", "OK", eventKey = "visible", wallMillis = now))
        assertTrue(repository.record("com.example.app", "incident", "LIMIT_CLAIMED", "OK", eventKey = "claimed", wallMillis = now - 1000))
        val events = repository.events(repository.incidents().single().incident)
        assertEquals(listOf("LIMIT_CLAIMED", "RESTRICTION_VISIBLE"), events.map { it.stage })
        assertEquals("RESTRICTION_VISIBLE", repository.events(events.first().incident, events.first().id).single().stage)
    }
}
