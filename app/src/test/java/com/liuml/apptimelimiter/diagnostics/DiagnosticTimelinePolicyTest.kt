package com.liuml.apptimelimiter.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticTimelinePolicyTest {
    @Test fun incidentIdentityIsStableAndPackageBound() {
        val first = DiagnosticTimelinePolicy.incidentKey("com.example.one", "incident-1")
        assertEquals(first, DiagnosticTimelinePolicy.incidentKey("com.example.one", "incident-1"))
        assertNotEquals(first, DiagnosticTimelinePolicy.incidentKey("com.example.two", "incident-1"))
        assertNotEquals(first, DiagnosticTimelinePolicy.incidentKey("com.example.one", "incident-2"))
        assertEquals(64, first!!.length)
        assertFalse(first.contains("incident"))
    }
    @Test fun rejectsUnboundedOrMissingCorrelation() {
        assertNull(DiagnosticTimelinePolicy.incidentKey("", "event"))
        assertNull(DiagnosticTimelinePolicy.incidentKey("com.example", " "))
        assertNull(DiagnosticTimelinePolicy.incidentKey("com.example", "x".repeat(241)))
        assertNull(DiagnosticTimelinePolicy.incidentKey("com.example\nforged", "event"))
    }
    @Test fun freeFormSensitiveMessagesCannotBecomeReasonCodes() {
        assertEquals("UNKNOWN", DiagnosticTimelinePolicy.code("PIN=1234"))
        assertEquals("UNKNOWN", DiagnosticTimelinePolicy.code("https://ad.example/?key=secret"))
        assertEquals("UNKNOWN", DiagnosticTimelinePolicy.code("SDK error\nsecret"))
        assertEquals("UNKNOWN", DiagnosticTimelinePolicy.code("X".repeat(97)))
        assertEquals("ad_load_timeout", DiagnosticTimelinePolicy.code("ad_load_timeout"))
    }
}
