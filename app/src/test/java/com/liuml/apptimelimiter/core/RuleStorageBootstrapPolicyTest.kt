package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleStorageBootstrapPolicyTest {
    @Test
    fun `first upgraded run adopts existing rules without clearing them`() {
        assertEquals(
            RuleStorageBootstrapAction.ADOPT_EXISTING,
            RuleStorageBootstrapPolicy.action(
                privateMarkerPresent = false,
                sharedMarkerPresent = false,
                primaryMarkerPresent = false,
            ),
        )
    }

    @Test
    fun `missing private marker with retained shared marker means app data was cleared`() {
        assertEquals(
            RuleStorageBootstrapAction.RESET_AFTER_DATA_CLEAR,
            RuleStorageBootstrapPolicy.action(
                privateMarkerPresent = false,
                sharedMarkerPresent = true,
                primaryMarkerPresent = false,
            ),
        )
    }

    @Test
    fun `existing primary store stays authoritative when framework mirror disappears`() {
        assertEquals(
            RuleStorageBootstrapAction.KEEP,
            RuleStorageBootstrapPolicy.action(
                privateMarkerPresent = true,
                sharedMarkerPresent = false,
                primaryMarkerPresent = true,
            ),
        )
    }

    @Test
    fun `existing primary store repairs an interrupted lifecycle marker write`() {
        assertEquals(
            RuleStorageBootstrapAction.KEEP,
            RuleStorageBootstrapPolicy.action(
                privateMarkerPresent = false,
                sharedMarkerPresent = true,
                primaryMarkerPresent = true,
            ),
        )
    }
}
