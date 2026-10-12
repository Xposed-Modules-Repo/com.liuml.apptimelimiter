package com.liuml.apptimelimiter.update

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AutomaticUpdatePolicyTest {
    @Test fun everyEntryChecksUnlessDisabled() {
        assertTrue(AutomaticUpdatePolicy.shouldCheck(true, 1000, 999))
        assertTrue(AutomaticUpdatePolicy.shouldCheck(true, 1000, 2000))
        assertFalse(AutomaticUpdatePolicy.shouldCheck(false, 1000, 0))
        assertFalse(AutomaticUpdatePolicy.shouldCheck(true, 0, 0))
    }
    @Test fun sameReleasePromptsOncePerLocalDayIncludingClockRollback() {
        val midnight = LocalDate.of(2026, 10, 9).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertFalse(AutomaticUpdatePolicy.shouldPrompt("v2", midnight + 3600000, "v2", midnight + 1000))
        assertTrue(AutomaticUpdatePolicy.shouldPrompt("v2", midnight + 1000, "v2", midnight - 1000))
        assertFalse(AutomaticUpdatePolicy.shouldPrompt("v2", midnight - 1000, "v2", midnight + 1000))
        assertTrue(AutomaticUpdatePolicy.shouldPrompt("v3", midnight, "v2", midnight))
        assertTrue(AutomaticUpdatePolicy.shouldPrompt("v2", midnight, null, 0))
    }
    @Test fun rotationCannotReclaimAnEntryButNextOpeningCan() {
        val id = MainEntryUpdatePolicy.nextEntry()
        assertTrue(MainEntryUpdatePolicy.isKnown(id))
        assertTrue(MainEntryUpdatePolicy.claim(id))
        assertFalse(MainEntryUpdatePolicy.claim(id))
        assertTrue(MainEntryUpdatePolicy.claim(MainEntryUpdatePolicy.nextEntry()))
        assertFalse(MainEntryUpdatePolicy.claim(id))
        assertFalse(MainEntryUpdatePolicy.isKnown(Long.MAX_VALUE))
    }
}
