package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.nonroot.NonRootPermissionIssue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRepairPromptPolicyTest {
    private val issues = listOf(NonRootPermissionIssue.USAGE_ACCESS_MISSING)

    @Test
    fun `prompt only applies to non root mode with targets and issues`() {
        assertFalse(decide(mode = ProtectionMode.XPOSED).shouldShow)
        assertFalse(decide(targetCount = 0).shouldShow)
        assertFalse(decide(issues = emptyList()).shouldShow)
        assertTrue(decide().shouldShow)
    }

    @Test
    fun `same signature is cooled down for 72 hours`() {
        val signature = PermissionRepairPromptPolicy.signature(
            ProtectionMode.ACCESSIBILITY,
            issues,
        )
        assertFalse(
            decide(
                lastShownSignature = signature,
                lastShownAtMillis = 1_000L,
                nowMillis = 1_000L + PermissionRepairPromptPolicy.DEFAULT_COOLDOWN_MILLIS - 1L,
            ).shouldShow,
        )
        assertTrue(
            decide(
                lastShownSignature = signature,
                lastShownAtMillis = 1_000L,
                nowMillis = 1_000L + PermissionRepairPromptPolicy.DEFAULT_COOLDOWN_MILLIS,
            ).shouldShow,
        )
    }

    @Test
    fun `suppressed signature stays hidden while changed issue can show`() {
        val signature = PermissionRepairPromptPolicy.signature(
            ProtectionMode.ACCESSIBILITY,
            issues,
        )
        assertFalse(decide(suppressed = setOf(signature)).shouldShow)
        val changed = PermissionRepairPromptPolicy.signature(
            ProtectionMode.ACCESSIBILITY,
            issues + NonRootPermissionIssue.ACCESSIBILITY_DISCONNECTED,
        )
        assertNotEquals(signature, changed)
        assertTrue(
            decide(
                issues = issues + NonRootPermissionIssue.ACCESSIBILITY_DISCONNECTED,
                suppressed = setOf(signature),
            ).shouldShow,
        )
    }

    @Test
    fun `transient accessibility disconnect gets a reconnect grace period`() {
        assertTrue(
            PermissionRepairPromptPolicy.initialDelayMillis(
                listOf(NonRootPermissionIssue.ACCESSIBILITY_DISCONNECTED),
            ) > 0L,
        )
        assertTrue(
            PermissionRepairPromptPolicy.initialDelayMillis(issues) == 0L,
        )
    }

    private fun decide(
        mode: ProtectionMode = ProtectionMode.ACCESSIBILITY,
        targetCount: Int = 1,
        issues: List<NonRootPermissionIssue> = this.issues,
        suppressed: Set<String> = emptySet(),
        lastShownSignature: String? = null,
        lastShownAtMillis: Long = 0L,
        nowMillis: Long = 10_000L,
    ) = PermissionRepairPromptPolicy.decide(
        mode = mode,
        targetCount = targetCount,
        issues = issues,
        suppressedSignatures = suppressed,
        lastShownSignature = lastShownSignature,
        lastShownAtMillis = lastShownAtMillis,
        nowMillis = nowMillis,
    )
}
