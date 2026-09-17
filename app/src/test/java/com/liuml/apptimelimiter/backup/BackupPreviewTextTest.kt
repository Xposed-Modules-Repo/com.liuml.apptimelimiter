package com.liuml.apptimelimiter.backup

import org.junit.Assert.*
import org.junit.Test

class BackupPreviewTextTest {
    @Test fun labelsBooleansAndDurationsAreReadableInBothLanguages() {
        val cn = BackupPreviewText(false)
        val en = BackupPreviewText(true)
        assertEquals("每日可用时长", cn.label("dailyLimitSeconds"))
        assertEquals("Daily allowance", en.label("dailyLimitSeconds"))
        assertEquals("开启", cn.value("enabled", "true"))
        assertEquals("Off", en.value("enabled", "false"))
        assertEquals("1小时 1分钟 1秒", cn.value("dailyLimitSeconds", "3661"))
        assertEquals("1 h 1 min 1 s", en.value("dailyLimitSeconds", "3661"))
        assertEquals("Not present", en.value("name", null))
    }

    @Test fun schedulesMembersQuotesAndEnumsDoNotShowJson() {
        val en = BackupPreviewText(true)
        val windows = """[{"days":[1,7],"startMinute":1380,"endMinute":60}]"""
        val value = en.value("scheduleWindows", windows)
        assertTrue(value.contains("Mon"))
        assertTrue(value.contains("Sun"))
        assertTrue(value.contains("23:00–01:00 (next day)"))
        assertEquals("• com.example.one\n• com.example.two",
            en.value("packageNames", """["com.example.one","com.example.two"]"""))
        assertEquals("• a → b", en.value("customTimeQuotes", """["a → b"]"""))
        assertEquals("Follow system", en.value("themeMode", "\"SYSTEM\""))
        assertEquals("仅在这些时段允许使用", BackupPreviewText(false).value("scheduleMode", "\"ALLOW_ONLY\""))
    }

    @Test fun bothStaleErrorCodesRequireNewPreviewInBothLanguages() {
        listOf("stale_preview", "IllegalStateException:${PortableBackupDiffPolicy.STALE_PREVIEW}").forEach { reason ->
            assertTrue(BackupPreviewText(false).error(reason).contains("重新预览"))
            assertTrue(BackupPreviewText(true).error(reason).contains("Refresh the preview"))
            assertFalse(BackupPreviewText(true).error(reason).contains("stale_preview"))
        }
    }
}
