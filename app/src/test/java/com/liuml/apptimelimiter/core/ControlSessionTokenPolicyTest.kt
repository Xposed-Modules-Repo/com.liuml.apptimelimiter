package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSessionTokenPolicyTest {
    private fun token(sessionId: String = "session") = ControlSessionToken(
        packageName = "com.example.app",
        groupId = "group-1",
        sessionId = sessionId,
        ruleVersion = 2L,
        groupVersion = 3L,
        protectionMode = ProtectionMode.ACCESSIBILITY,
        modeGeneration = 4L,
        foregroundGeneration = 5L,
    )

    @Test
    fun equalTokensMatch() {
        assertTrue(ControlSessionTokenPolicy.matches(token(), token()))
    }

    @Test
    fun changedSessionDoesNotMatch() {
        assertFalse(ControlSessionTokenPolicy.matches(token(), token("new")))
    }

    @Test
    fun blankIdentityIsRejected() {
        assertFalse(
            ControlSessionTokenPolicy.isStructurallyValid(token("") ),
        )
    }
}
