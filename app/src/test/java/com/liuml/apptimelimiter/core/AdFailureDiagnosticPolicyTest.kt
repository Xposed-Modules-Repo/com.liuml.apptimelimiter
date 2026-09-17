package com.liuml.apptimelimiter.core

import org.junit.Assert.*
import org.junit.Test

class AdFailureDiagnosticPolicyTest {
    @Test fun genericSourceFailureIsNotClaimedAsNoFill() {
        assertEquals("4001:category_source_error", AdFailureDiagnosticPolicy.signature("4001", "", ""))
    }
    @Test fun nestedCodesArePreservedWithoutPayload() {
        val value = AdFailureDiagnosticPolicy.signature("4001", "", "ad_source_id[secret] code:[4007] platformCode:[1002] platformMSG:[no fill https://private.example/device]")
        assertEquals("4001:codes_4007_1002:category_no_fill", value)
        assertFalse(value.contains("secret"))
        assertFalse(value.contains("private"))
    }
    @Test fun malformedCodesAreDiscarded() {
        assertEquals("unknown:category_network", AdFailureDiagnosticPolicy.signature("https://secret", "device-id", "SSLHandshake failed"))
    }
}
