package com.liuml.apptimelimiter.nonroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentIncidentPolicyTest {
    @Test
    fun `same incident is claimed only once across restored history`() {
        val first = PersistentIncidentPolicy.claim(
            existingIncidentIds = emptyList(),
            incidentId = "schedule:com.example:7",
            maxEntries = 4,
        )
        val restored = PersistentIncidentPolicy.claim(
            existingIncidentIds = first.handledIncidentIds,
            incidentId = "schedule:com.example:7",
            maxEntries = 4,
        )

        assertTrue(first.isNewIncident)
        assertFalse(restored.isNewIncident)
        assertEquals(first.handledIncidentIds, restored.handledIncidentIds)
    }

    @Test
    fun `history removes oldest incidents and rejects line injection`() {
        val bounded = PersistentIncidentPolicy.claim(
            existingIncidentIds = listOf("one", "two", "three"),
            incidentId = "four",
            maxEntries = 3,
        )
        val invalid = PersistentIncidentPolicy.claim(
            existingIncidentIds = bounded.handledIncidentIds,
            incidentId = "bad\nincident",
            maxEntries = 3,
        )

        assertEquals(listOf("two", "three", "four"), bounded.handledIncidentIds)
        assertTrue(bounded.isNewIncident)
        assertFalse(invalid.isNewIncident)
        assertEquals(bounded.handledIncidentIds, invalid.handledIncidentIds)
    }
}
