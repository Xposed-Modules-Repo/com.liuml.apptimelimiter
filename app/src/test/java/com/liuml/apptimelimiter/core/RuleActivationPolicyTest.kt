package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleActivationPolicyTest {
    @Test
    fun `disabled personal quota fields do not remain executable`() {
        val rule = AppRule(
            packageName = "app.target",
            enabled = false,
            dailyEnabled = true,
            perLaunchEnabled = true,
        )

        assertFalse(RuleActivationPolicy.hasEffectiveRule(rule, null))
    }

    @Test
    fun `session planning alone remains executable`() {
        val rule = AppRule(packageName = "app.target", sessionPlanningEnabled = true)

        assertTrue(RuleActivationPolicy.hasEffectiveRule(rule, null))
    }

    @Test
    fun `assigned group suppresses a legacy personal rule even when group is disabled`() {
        val rule = AppRule(packageName = "app.target", enabled = true, dailyEnabled = true)
        val group = AppGroup(
            id = "group",
            name = "Group",
            enabled = false,
            packageNames = setOf(rule.packageName),
        )

        assertFalse(RuleActivationPolicy.hasEffectiveRule(rule, group))
    }

    @Test
    fun `active group rule manages its member`() {
        val rule = AppRule(packageName = "app.target")
        val group = AppGroup(
            id = "group",
            name = "Group",
            enabled = true,
            dailyEnabled = true,
            packageNames = setOf(rule.packageName),
        )

        assertTrue(RuleActivationPolicy.hasEffectiveRule(rule, group))
    }
}
