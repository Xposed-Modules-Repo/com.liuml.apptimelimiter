package com.liuml.apptimelimiter.backup

import org.json.JSONArray
import org.json.JSONTokener
import java.util.Locale

/** Presentation only: never use formatted values for comparison or persistence. */
class BackupPreviewText(private val english: Boolean) {
    fun text(chinese: String, english: String) = if (this.english) english else chinese
    fun error(reason: String): String = if (PortableBackupDiffPolicy.isStalePreview(reason)) {
        text("当前配置已变化，已拒绝导入。请重新预览后确认。", "The current configuration changed. Import was rejected. Refresh the preview and confirm again.")
    } else reason
    fun change(value: BackupChange) = when (value) {
        BackupChange.ADDED -> text("新增", "Added")
        BackupChange.MODIFIED -> text("修改", "Modified")
        BackupChange.DELETED -> text("删除", "Deleted")
        BackupChange.UNCHANGED -> text("未变化", "Unchanged")
    }

    fun label(field: String): String = labels[field]?.let { text(it.first, it.second) }
        ?: text("其他配置（见高级详情）", "Other configuration (see advanced details)")

    fun value(field: String, raw: String?): String {
        if (raw == null) return text("不存在", "Not present")
        return runCatching {
            val parsed = JSONTokener(raw).nextValue()
            when {
                parsed is Boolean -> if (parsed) text("开启", "On") else text("关闭", "Off")
                field.endsWith("Seconds") && parsed is Number -> duration(parsed.toLong())
                field == "scheduleWindows" -> {
                    val windows = parsed as JSONArray
                    if (windows.length() == 0) text("无时段", "No time windows") else
                        (0 until windows.length()).joinToString("\n") { i ->
                            val window = windows.getJSONObject(i)
                            val days = window.getJSONArray("days")
                            val weekdays = if (english) listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                                else listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                            val start = window.getInt("startMinute")
                            val end = window.getInt("endMinute")
                            (0 until days.length()).joinToString(text("、", ", ")) { weekdays[days.getInt(it) - 1] } +
                                " ${clock(start)}–${clock(end)}" +
                                if (end < start) text("（次日）", " (next day)") else ""
                        }
                }
                parsed is JSONArray -> if (parsed.length() == 0) text("无", "None") else
                    (0 until parsed.length()).joinToString("\n") { "• ${parsed.getString(it)}" }
                field in setOf("extensionDailyLimit", "extensionSessionLimit", "extensionFreeDailyLimit") ->
                    text("$parsed 次", "$parsed times")
                parsed is String -> enumLabel(field, parsed)
                parsed is Number -> parsed.toString()
                else -> text("见高级详情", "See advanced details")
            }
        }.getOrElse { text("无法显示，请查看高级详情", "Unable to display; see advanced details") }
    }

    private fun duration(seconds: Long): String {
        val parts = mutableListOf<String>()
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val rest = seconds % 60
        if (hours != 0L) parts += text("${hours}小时", "$hours h")
        if (minutes != 0L) parts += text("${minutes}分钟", "$minutes min")
        if (rest != 0L || parts.isEmpty()) parts += text("${rest}秒", "$rest s")
        return parts.joinToString(" ")
    }

    private fun clock(minutes: Int) = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    private fun enumLabel(field: String, value: String): String = when (field to value) {
        "scheduleMode" to "ALLOW_ONLY" -> text("仅在这些时段允许使用", "Allow only during these windows")
        "scheduleMode" to "BLOCK_DURING" -> text("在这些时段禁止使用", "Block during these windows")
        "languageMode" to "SYSTEM", "themeMode" to "SYSTEM" -> text("跟随系统", "Follow system")
        "languageMode" to "SIMPLIFIED_CHINESE" -> text("简体中文", "Simplified Chinese")
        "languageMode" to "ENGLISH" -> text("英语", "English")
        "themeMode" to "LIGHT" -> text("浅色", "Light")
        "themeMode" to "DARK" -> text("深色", "Dark")
        "themeColor" to "GREEN" -> text("绿色", "Green")
        "themeColor" to "BLUE" -> text("蓝色", "Blue")
        "themeColor" to "PURPLE" -> text("紫色", "Purple")
        else -> value
    }

    private companion object {
        val labels = mapOf(
            "packageName" to ("应用包名" to "App package"), "id" to ("分组标识" to "Group ID"),
            "name" to ("分组名称" to "Group name"), "packageNames" to ("分组成员" to "Group members"),
            "enabled" to ("启用规则" to "Rule enabled"),
            "sessionPlanningEnabled" to ("本次使用计划" to "Session planning"),
            "dailyEnabled" to ("每日限额" to "Daily limit enabled"),
            "dailyLimitSeconds" to ("每日可用时长" to "Daily allowance"),
            "perLaunchEnabled" to ("单次打开限额" to "Per-launch limit enabled"),
            "perLaunchLimitSeconds" to ("单次可用时长" to "Per-launch allowance"),
            "scheduleEnabled" to ("时段限制" to "Schedule enabled"),
            "scheduleMode" to ("时段规则" to "Schedule mode"), "scheduleWindows" to ("时段" to "Time windows"),
            "cooldownEnabled" to ("退出后冷却" to "Cooldown enabled"),
            "cooldownSeconds" to ("冷却时长" to "Cooldown duration"),
            "exitWarningEnabled" to ("退出提醒" to "Exit warning"),
            "fullScreenExitWarningEnabled" to ("全屏退出提醒" to "Full-screen exit warning"),
            "exitWarningVibrationEnabled" to ("退出提醒振动" to "Exit warning vibration"),
            "usageMilestoneReminderEnabled" to ("使用时长提醒" to "Usage milestone reminder"),
            "openUsageTipEnabled" to ("打开时使用提示" to "App-open usage tip"),
            "languageMode" to ("语言" to "Language"), "themeMode" to ("显示模式" to "Appearance"),
            "themeColor" to ("主题颜色" to "Theme color"), "timeQuotesEnabled" to ("时间短句" to "Time quotes"),
            "builtInTimeQuotesEnabled" to ("内置短句" to "Built-in quotes"),
            "customTimeQuotes" to ("自定义短句" to "Custom quotes"),
            "automaticUpdateCheckEnabled" to ("自动检查更新" to "Automatic update checks"),
            "extensionEnabled" to ("延时退出" to "Extensions enabled"),
            "extensionSeconds" to ("每次延长时长" to "Extension duration"),
            "extensionDailyLimit" to ("每日延时次数" to "Daily extension limit"),
            "extensionSessionLimit" to ("单次会话延时次数" to "Session extension limit"),
            "extensionFreeDailyLimit" to ("每日免费延时次数" to "Daily free extension limit"),
            "diagnosticsEnabled" to ("诊断记录" to "Diagnostics"),
            "usageStatsEnabled" to ("使用统计" to "Usage statistics"),
        )
    }
}
