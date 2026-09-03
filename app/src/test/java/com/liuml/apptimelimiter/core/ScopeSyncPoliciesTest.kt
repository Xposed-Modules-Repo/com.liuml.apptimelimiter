package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.ProtectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScopeSyncPoliciesTest {
    @Test
    fun desiredScopeIncludesEnabledPersonalAndPlanOnlyRules() {
        val result = DesiredScopePolicy.compute(
            rules = listOf(
                AppRule("example.daily", enabled = true, dailyEnabled = true),
                AppRule("example.plan", sessionPlanningEnabled = true),
                AppRule("example.off"),
                AppRule("com.liuml.apptimelimiter", enabled = true, dailyEnabled = true),
            ),
            groups = emptyList(),
            modulePackageName = "com.liuml.apptimelimiter",
        )

        assertEquals(setOf("example.daily", "example.plan"), result)
    }

    @Test
    fun enabledGroupMembersUseGroupRuleAndDisabledGroupSuppressesLegacyConflict() {
        val activeGroup = AppGroup(
            id = "active",
            name = "Active",
            enabled = true,
            dailyEnabled = true,
            packageNames = setOf("example.group"),
        )
        val disabledGroup = AppGroup(
            id = "disabled",
            name = "Disabled",
            enabled = false,
            dailyEnabled = true,
            packageNames = setOf("example.legacy"),
        )
        val result = DesiredScopePolicy.compute(
            rules = listOf(
                AppRule("example.group"),
                AppRule("example.legacy", enabled = true, dailyEnabled = true),
            ),
            groups = listOf(activeGroup, disabledGroup),
            modulePackageName = "com.liuml.apptimelimiter",
        )

        assertEquals(setOf("example.group"), result)
    }

    @Test
    fun reconciliationOnlyRemovesPackagesPreviouslyManagedByTimeStop() {
        val plan = ScopeReconciliationPolicy.resolve(
            desiredPackages = setOf("example.keep", "example.add"),
            actualPackages = setOf("example.keep", "example.remove", "example.manual"),
            managedPackages = setOf("example.keep", "example.remove"),
            protectionMode = ProtectionMode.XPOSED,
            frameworkReadable = true,
        )

        assertEquals(setOf("example.add"), plan.requestPackages)
        assertEquals(setOf("example.remove"), plan.removePackages)
        assertEquals(setOf("example.keep"), plan.keepPackages)
    }

    @Test
    fun nonRootRetainsExistingScopeAndDoesNotRequestMissingPackages() {
        val plan = ScopeReconciliationPolicy.resolve(
            desiredPackages = setOf("example.keep", "example.missing"),
            actualPackages = setOf("example.keep"),
            managedPackages = setOf("example.keep"),
            protectionMode = ProtectionMode.ACCESSIBILITY,
            frameworkReadable = true,
        )

        assertEquals(emptySet<String>(), plan.requestPackages)
        assertEquals(emptySet<String>(), plan.removePackages)
        assertEquals(setOf("example.keep"), plan.keepPackages)
    }

    @Test
    fun unreadableFrameworkNeverProducesDestructiveActions() {
        val plan = ScopeReconciliationPolicy.resolve(
            desiredPackages = emptySet(),
            actualPackages = setOf("example.app"),
            managedPackages = setOf("example.app"),
            protectionMode = ProtectionMode.XPOSED,
            frameworkReadable = false,
        )

        assertEquals(emptySet<String>(), plan.requestPackages)
        assertEquals(emptySet<String>(), plan.removePackages)
    }

    @Test
    fun generationChangesWithTargetSetOrProtectionGeneration() {
        val first = DesiredScopePolicy.generation(setOf("example.app"), 1L)
        val changedSet = DesiredScopePolicy.generation(emptySet(), 1L)
        val changedMode = DesiredScopePolicy.generation(setOf("example.app"), 2L)

        assertNotEquals(first, changedSet)
        assertNotEquals(first, changedMode)
    }

    @Test
    fun removingAndReaddingSameTargetCreatesANewTargetGeneration() {
        val appFingerprint = DesiredScopePolicy.generation(setOf("example.app"), 1L)
        val emptyFingerprint = DesiredScopePolicy.generation(emptySet(), 1L)
        val first = ScopeTargetGenerationPolicy.resolve(null, 0L, appFingerprint)
        val removed = ScopeTargetGenerationPolicy.resolve(
            first.fingerprint,
            first.counter,
            emptyFingerprint,
        )
        val readded = ScopeTargetGenerationPolicy.resolve(
            removed.fingerprint,
            removed.counter,
            appFingerprint,
        )

        assertEquals(1L, first.counter)
        assertEquals(2L, removed.counter)
        assertEquals(3L, readded.counter)
    }

    @Test
    fun partialApprovalKeepsOnlyUnapprovedPackagesPending() {
        val result = ScopeApprovalPolicy.resolve(
            requestedPackages = setOf("example.one", "example.two"),
            approvedPackages = setOf("example.one", "unexpected.package"),
        )

        assertEquals(setOf("example.one"), result.approvedPackages)
        assertEquals(setOf("example.two"), result.pendingPackages)
    }
}
