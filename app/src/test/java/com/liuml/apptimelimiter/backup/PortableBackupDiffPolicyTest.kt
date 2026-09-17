package com.liuml.apptimelimiter.backup

import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.ScheduleWindow
import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.data.AppLanguageMode
import com.liuml.apptimelimiter.data.AppThemeMode
import com.liuml.apptimelimiter.data.AppThemeColor
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class PortableBackupDiffPolicyTest {
    @Test fun everyModelFieldIsSerializedExceptRuntimeVersions() {
        val source = backup()
        val body = PortableBackupCodec.bodyToJson(source)
        listOf(
            source.rules[0] to body.getJSONArray("rules").getJSONObject(0),
            source.groups[0] to body.getJSONArray("groups").getJSONObject(0),
            source.settings to body.getJSONObject("settings"),
        ).forEach { (model, json) ->
            val fields = model.javaClass.declaredFields.filterNot {
                it.isSynthetic || Modifier.isStatic(it.modifiers) || it.name == "version"
            }.map { it.name }.toSet()
            assertEquals(fields, json.keys().asSequence().toSet())
        }
    }

    @Test fun everyPortableValueAffectsFingerprintEvenWhenDisabled() {
        val old = backup()
        val rule = old.rules[1] // All restrictions disabled; stored values must still count.
        val ruleVariants = listOf(
            rule.copy(packageName = "com.example.other"), rule.copy(enabled = true),
            rule.copy(sessionPlanningEnabled = true), rule.copy(dailyEnabled = true),
            rule.copy(dailyLimitSeconds = 123), rule.copy(perLaunchEnabled = true),
            rule.copy(perLaunchLimitSeconds = 234), rule.copy(scheduleEnabled = true),
            rule.copy(scheduleMode = ScheduleMode.ALLOW_ONLY),
            rule.copy(scheduleWindows = listOf(ScheduleWindow(setOf(1, 3), 90, 120))),
            rule.copy(cooldownEnabled = true), rule.copy(cooldownSeconds = 456),
        )
        val group = old.groups[0]
        val groupVariants = listOf(
            group.copy(id = "other"), group.copy(name = "Other"), group.copy(enabled = false),
            group.copy(dailyEnabled = false), group.copy(dailyLimitSeconds = 123),
            group.copy(perLaunchEnabled = true), group.copy(perLaunchLimitSeconds = 234),
            group.copy(scheduleEnabled = true), group.copy(scheduleMode = ScheduleMode.ALLOW_ONLY),
            group.copy(scheduleWindows = listOf(ScheduleWindow(setOf(2), 90, 120))),
            group.copy(cooldownEnabled = true), group.copy(cooldownSeconds = 456),
            group.copy(packageNames = setOf("com.example.other")),
        )
        val settings = old.settings
        val settingVariants = listOf(
            settings.copy(exitWarningEnabled = false), settings.copy(fullScreenExitWarningEnabled = true),
            settings.copy(exitWarningVibrationEnabled = true), settings.copy(usageMilestoneReminderEnabled = true),
            settings.copy(openUsageTipEnabled = false), settings.copy(languageMode = AppLanguageMode.ENGLISH),
            settings.copy(themeMode = AppThemeMode.DARK), settings.copy(themeColor = AppThemeColor.BLUE),
            settings.copy(timeQuotesEnabled = false), settings.copy(builtInTimeQuotesEnabled = false),
            settings.copy(customTimeQuotes = listOf("quote")), settings.copy(automaticUpdateCheckEnabled = false),
            settings.copy(extensionEnabled = false), settings.copy(extensionSeconds = 123),
            settings.copy(extensionDailyLimit = 7), settings.copy(extensionSessionLimit = 2),
            settings.copy(extensionFreeDailyLimit = 1), settings.copy(diagnosticsEnabled = false),
            settings.copy(usageStatsEnabled = false),
        )
        val variants = ruleVariants.map { old.copy(rules = listOf(old.rules[0], it)) } +
            groupVariants.map { old.copy(groups = listOf(it)) } + settingVariants.map { old.copy(settings = it) }
        variants.forEachIndexed { index, variant ->
            assertNotEquals("portable variant $index", PortableBackupDiffPolicy.fingerprint(old), PortableBackupDiffPolicy.fingerprint(variant))
        }
    }

    @Test fun fingerprintDoesNotTrimDeduplicateOrTruncateActualQuotes() {
        val old = backup().copy(settings = backup().settings.copy(customTimeQuotes = listOf("quote")))
        listOf(listOf(" quote "), listOf("quote", "quote"), listOf("quote", "")).forEach { quotes ->
            assertNotEquals(PortableBackupDiffPolicy.fingerprint(old),
                PortableBackupDiffPolicy.fingerprint(old.copy(settings = old.settings.copy(customTimeQuotes = quotes))))
        }
    }

    @Test fun everyScheduleFieldCountsButDaySetOrderDoesNot() {
        val window = ScheduleWindow(linkedSetOf(1, 3), 60, 120)
        val old = backup().copy(rules = listOf(backup().rules[0].copy(scheduleWindows = listOf(window))))
        listOf(window.copy(daysOfWeek = setOf(2)), window.copy(startMinute = 61), window.copy(endMinute = 121)).forEach {
            val changed = old.copy(rules = listOf(old.rules[0].copy(scheduleWindows = listOf(it))))
            assertNotEquals(PortableBackupDiffPolicy.fingerprint(old), PortableBackupDiffPolicy.fingerprint(changed))
        }
        val reordered = window.copy(daysOfWeek = linkedSetOf(3, 1))
        val same = old.copy(rules = listOf(old.rules[0].copy(scheduleWindows = listOf(reordered))))
        assertEquals(PortableBackupDiffPolicy.fingerprint(old), PortableBackupDiffPolicy.fingerprint(same))
    }

    private fun backup() = PortableBackupV1(
        1L, "test", 1,
        listOf(AppRule("com.example.one", dailyEnabled = true), AppRule("com.example.two")),
        listOf(AppGroup(id = "group", name = "Group", packageNames = setOf("com.example.three"))),
        PortableGlobalSettings(),
    )

    @Test fun rulesReportAllFourStatesAndBeforeAfterValues() {
        val old = backup().copy(rules = backup().rules + AppRule("com.example.same"))
        val new = old.copy(rules = listOf(old.rules[0].copy(dailyLimitSeconds = 123L),
            AppRule("com.example.new"), old.rules[2]))
        val diff = PortableBackupDiffPolicy.compare(old, new).rules.associateBy { it.key }
        assertEquals(BackupChange.ADDED, diff.getValue("com.example.new").change)
        assertEquals(BackupChange.DELETED, diff.getValue("com.example.two").change)
        assertEquals(BackupChange.UNCHANGED, diff.getValue("com.example.same").change)
        val changed = diff.getValue("com.example.one")
        assertEquals(BackupChange.MODIFIED, changed.change)
        val field = changed.fields.single { it.name == "dailyLimitSeconds" }
        assertEquals(old.rules[0].dailyLimitSeconds.toString(), field.before)
        assertEquals("123", field.after)
        assertTrue(diff.getValue("com.example.new").fields.all { it.before == null })
        assertTrue(diff.getValue("com.example.two").fields.all { it.after == null })
    }

    @Test fun groupsReportAllFourStatesIncludingMembershipChanges() {
        val group = backup().groups.single()
        val old = backup().copy(groups = listOf(group, group.copy(id = "deleted"), group.copy(id = "same")))
        val new = old.copy(groups = listOf(group.copy(packageNames = setOf("com.example.other")),
            group.copy(id = "added"), old.groups[2]))
        val changes = PortableBackupDiffPolicy.compare(old, new).groups.associate { it.key to it.change }
        assertEquals(mapOf("group" to BackupChange.MODIFIED, "deleted" to BackupChange.DELETED,
            "added" to BackupChange.ADDED, "same" to BackupChange.UNCHANGED), changes)
    }

    @Test fun settingsIncludeUnchangedAndExactValues() {
        val old = backup()
        val new = old.copy(settings = old.settings.copy(openUsageTipEnabled = false, customTimeQuotes = listOf("a → b")))
        val diff = PortableBackupDiffPolicy.compare(old, new).settings
        assertEquals(2, diff.count { it.change == BackupChange.MODIFIED })
        assertTrue(diff.any { it.change == BackupChange.UNCHANGED })
        assertEquals(BackupFieldDiff("openUsageTipEnabled", "true", "false"),
            diff.single { it.key == "openUsageTipEnabled" }.fields.single())
    }

    @Test fun fingerprintIgnoresMetadataVersionsAndSetOrder() {
        val old = backup().copy(groups = listOf(backup().groups[0].copy(packageNames = linkedSetOf("com.example.a", "com.example.b"))))
        val new = old.copy(createdAtMillis = 999L, sourceVersionName = "another", sourceVersionCode = 99,
            rules = old.rules.reversed().map { it.copy(version = 123L) },
            groups = listOf(old.groups[0].copy(version = 999L, packageNames = linkedSetOf("com.example.b", "com.example.a"))))
        assertEquals(PortableBackupDiffPolicy.fingerprint(old), PortableBackupDiffPolicy.fingerprint(new))
        PortableBackupDiffPolicy.requireCurrent(PortableBackupDiffPolicy.fingerprint(old), new)
        assertTrue(PortableBackupDiffPolicy.compare(old, new).rules.all { it.change == BackupChange.UNCHANGED })
    }

    @Test fun fingerprintDetectsEveryConfigurationCategory() {
        val old = backup()
        val variants = listOf(old.copy(rules = emptyList()), old.copy(groups = emptyList()),
            old.copy(settings = old.settings.copy(extensionSeconds = 321L)),
            old.copy(rules = listOf(old.rules[0].copy(scheduleWindows = listOf(ScheduleWindow(setOf(1), 100, 200))))))
        variants.forEach { assertNotEquals(PortableBackupDiffPolicy.fingerprint(old), PortableBackupDiffPolicy.fingerprint(it)) }
    }

    @Test fun stalePreviewRejectedAndRefreshedFingerprintAccepted() {
        val old = backup()
        val changed = old.copy(settings = old.settings.copy(exitWarningEnabled = false))
        try {
            PortableBackupDiffPolicy.requireCurrent(PortableBackupDiffPolicy.fingerprint(old), changed)
            fail("stale preview accepted")
        } catch (error: IllegalStateException) {
            assertEquals(PortableBackupDiffPolicy.STALE_PREVIEW, error.message)
        }
        PortableBackupDiffPolicy.requireCurrent(PortableBackupDiffPolicy.fingerprint(changed), changed)
    }

    @Test fun emptyReplacementReportsAllDeletionsAndKeepsSettings() {
        val old = backup()
        val diff = PortableBackupDiffPolicy.compare(old, old.copy(rules = emptyList(), groups = emptyList()))
        assertTrue((diff.rules + diff.groups).all { it.change == BackupChange.DELETED })
        assertTrue(diff.settings.all { it.change == BackupChange.UNCHANGED })
    }
}
