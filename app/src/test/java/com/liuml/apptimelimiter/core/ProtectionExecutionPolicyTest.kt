package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionExecutionPolicyTest {
    @Test
    fun `LSPosed and non root execution are mutually exclusive`() {
        assertTrue(ProtectionExecutionPolicy.hookMayExecute(ProtectionMode.XPOSED))
        assertFalse(ProtectionExecutionPolicy.nonRootMayExecute(ProtectionMode.XPOSED))

        listOf(
            ProtectionMode.ACCESSIBILITY,
            ProtectionMode.ACCESSIBILITY_SHIZUKU,
        ).forEach { mode ->
            assertFalse(ProtectionExecutionPolicy.hookMayExecute(mode))
            assertFalse(ProtectionExecutionPolicy.acceptHookSideEffect(mode))
            assertTrue(ProtectionExecutionPolicy.nonRootMayExecute(mode))
            assertFalse(
                ProtectionExecutionPolicy.ruleSnapshotMayExecute(
                    mode,
                    trustedNonRootRequest = false,
                ),
            )
            assertTrue(
                ProtectionExecutionPolicy.ruleSnapshotMayExecute(
                    mode,
                    trustedNonRootRequest = true,
                ),
            )
        }
        assertFalse(
            ProtectionExecutionPolicy.ruleSnapshotMayExecute(
                ProtectionMode.XPOSED,
                trustedNonRootRequest = true,
            ),
        )
    }
}
