package com.liuml.apptimelimiter.core

import org.junit.Assert.*
import org.junit.Test

class UsageReminderTextPolicyTest {
    @Test fun actualUsageAndIndividualBalancesAreFormattedAsTime() {
        val text = UsageReminderTextPolicy.message(UsageReminderDisplay("抖音", 3720000,
            RuleDecisionSnapshot(appDailyRemainingMillis = 3600000, appPerLaunchRemainingMillis = 480000)), false)
        assertEquals("抖音 · 今日累计使用 1 小时 2 分钟\n本轮可用时间：还剩 8 分钟\n今日可用额度：还剩 1 小时", text)
    }
    @Test fun groupedSnapshotIgnoresPausedIndividualRules() {
        val text = UsageReminderTextPolicy.message(UsageReminderDisplay("App", 1800000,
            RuleDecisionSnapshot(grouped = true, appDailyRemainingMillis = 0,
                groupDailyRemainingMillis = 600000, groupPerLaunchRemainingMillis = 300000)), true)
        assertTrue(text.contains("Shared group session allowance: 5 min left"))
        assertTrue(text.contains("Shared group daily allowance: 10 min left"))
        assertFalse(text.contains("exhausted"))
    }
    @Test fun mixedRulesUseTighterBalanceAndKeepItsSource() {
        val text = UsageReminderTextPolicy.message(UsageReminderDisplay("App", null,
            RuleDecisionSnapshot(appDailyRemainingMillis = 900000, groupDailyRemainingMillis = 600000,
                appPerLaunchRemainingMillis = 120000, groupPerLaunchRemainingMillis = 300000)), true)
        assertEquals("Session allowance: 2 min left\nShared group daily allowance: 10 min left", text)
    }
    @Test fun missingInvalidAndExhaustedBalancesNeverBecomeNegativeTimes() {
        val text = UsageReminderTextPolicy.message(UsageReminderDisplay("App", -1,
            RuleDecisionSnapshot(appDailyRemainingMillis = 0, appPerLaunchRemainingMillis = -1,
                planRemainingMillis = Long.MAX_VALUE)), false)
        assertEquals("今日可用额度：已用完", text)
    }
    @Test fun pinShowsFixedAllowanceInsteadOfOrdinaryBalances() {
        val text = UsageReminderTextPolicy.message(UsageReminderDisplay("App", 1800000,
            RuleDecisionSnapshot(appDailyRemainingMillis = 0), pinRemainingMillis = 2520000), false)
        assertTrue(text.contains("临时放行有效时间：还剩 42 分钟"))
        assertFalse(text.contains("已用完"))
    }
}
