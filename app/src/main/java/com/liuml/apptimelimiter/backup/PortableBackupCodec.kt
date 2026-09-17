package com.liuml.apptimelimiter.backup

import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppLanguageMode
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.AppThemeColor
import com.liuml.apptimelimiter.data.AppThemeMode
import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.core.ExtensionQuotaPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object PortableBackupCodec {
    fun encode(backup: PortableBackupV1): String {
        val normalized = PortableBackupPolicy.normalize(backup)
        val body = bodyToJson(normalized)
        val canonicalBody = body.toString()
        return JSONObject()
            .put("format", PortableBackupPolicy.FORMAT)
            .put("schema", PortableBackupPolicy.SCHEMA_VERSION)
            .put("body", body)
            .put("sha256", sha256(canonicalBody))
            .toString(2)
    }

    fun decode(text: String): PortableBackupV1 {
        require(text.toByteArray(StandardCharsets.UTF_8).size <= PortableBackupPolicy.MAX_BACKUP_BYTES) {
            "backup_too_large"
        }
        val root = JSONObject(text)
        require(root.optString("format") == PortableBackupPolicy.FORMAT) { "unsupported_format" }
        require(root.optInt("schema", -1) == PortableBackupPolicy.SCHEMA_VERSION) {
            "unsupported_schema"
        }
        val body = root.getJSONObject("body")
        val backup = bodyFromJson(body)
        val canonical = bodyToJson(PortableBackupPolicy.normalize(backup)).toString()
        require(root.optString("sha256").equals(sha256(canonical), ignoreCase = true)) {
            "integrity_mismatch"
        }
        return PortableBackupPolicy.normalize(backup)
    }

    internal fun bodyToJson(backup: PortableBackupV1): JSONObject = JSONObject()
        .put("createdAtMillis", backup.createdAtMillis)
        .put("sourceVersionName", backup.sourceVersionName)
        .put("sourceVersionCode", backup.sourceVersionCode)
        .put("rules", JSONArray().apply { backup.rules.forEach { put(ruleToJson(it)) } })
        .put("groups", JSONArray().apply { backup.groups.forEach { put(groupToJson(it)) } })
        .put("settings", settingsToJson(backup.settings))

    private fun bodyFromJson(value: JSONObject): PortableBackupV1 = PortableBackupV1(
        createdAtMillis = value.getLong("createdAtMillis"),
        sourceVersionName = value.getString("sourceVersionName"),
        sourceVersionCode = value.getInt("sourceVersionCode"),
        rules = value.getJSONArray("rules").objects(::ruleFromJson),
        groups = value.getJSONArray("groups").objects(::groupFromJson),
        settings = settingsFromJson(value.getJSONObject("settings")),
    )

    private fun ruleToJson(rule: AppRule): JSONObject = JSONObject()
        .put("packageName", rule.packageName)
        .put("enabled", rule.enabled)
        .put("sessionPlanningEnabled", rule.sessionPlanningEnabled)
        .put("dailyEnabled", rule.dailyEnabled)
        .put("dailyLimitSeconds", rule.dailyLimitSeconds)
        .put("perLaunchEnabled", rule.perLaunchEnabled)
        .put("perLaunchLimitSeconds", rule.perLaunchLimitSeconds)
        .put("scheduleEnabled", rule.scheduleEnabled)
        .put("scheduleMode", rule.scheduleMode.name)
        .put("scheduleWindows", windowsToJson(rule.scheduleWindows))
        .put("cooldownEnabled", rule.cooldownEnabled)
        .put("cooldownSeconds", rule.cooldownSeconds)

    private fun ruleFromJson(value: JSONObject): AppRule = AppRule(
        packageName = value.getString("packageName"),
        enabled = value.optBoolean("enabled", false),
        sessionPlanningEnabled = value.optBoolean("sessionPlanningEnabled", false),
        dailyEnabled = value.optBoolean("dailyEnabled", false),
        dailyLimitSeconds = value.getLong("dailyLimitSeconds"),
        perLaunchEnabled = value.optBoolean("perLaunchEnabled", false),
        perLaunchLimitSeconds = value.getLong("perLaunchLimitSeconds"),
        scheduleEnabled = value.optBoolean("scheduleEnabled", false),
        scheduleMode = enum(value.optString("scheduleMode"), ScheduleMode.BLOCK_DURING),
        scheduleWindows = windowsFromJson(value.getJSONArray("scheduleWindows")),
        cooldownEnabled = value.optBoolean("cooldownEnabled", false),
        cooldownSeconds = value.getLong("cooldownSeconds"),
    )

    private fun groupToJson(group: AppGroup): JSONObject = JSONObject()
        .put("id", group.id)
        .put("name", group.name)
        .put("enabled", group.enabled)
        .put("dailyEnabled", group.dailyEnabled)
        .put("dailyLimitSeconds", group.dailyLimitSeconds)
        .put("perLaunchEnabled", group.perLaunchEnabled)
        .put("perLaunchLimitSeconds", group.perLaunchLimitSeconds)
        .put("scheduleEnabled", group.scheduleEnabled)
        .put("scheduleMode", group.scheduleMode.name)
        .put("scheduleWindows", windowsToJson(group.scheduleWindows))
        .put("cooldownEnabled", group.cooldownEnabled)
        .put("cooldownSeconds", group.cooldownSeconds)
        .put("packageNames", JSONArray(group.packageNames.sorted()))

    private fun groupFromJson(value: JSONObject): AppGroup = AppGroup(
        id = value.getString("id"),
        name = value.getString("name"),
        enabled = value.optBoolean("enabled", true),
        dailyEnabled = value.optBoolean("dailyEnabled", true),
        dailyLimitSeconds = value.getLong("dailyLimitSeconds"),
        perLaunchEnabled = value.optBoolean("perLaunchEnabled", false),
        perLaunchLimitSeconds = value.getLong("perLaunchLimitSeconds"),
        scheduleEnabled = value.optBoolean("scheduleEnabled", false),
        scheduleMode = enum(value.optString("scheduleMode"), ScheduleMode.BLOCK_DURING),
        scheduleWindows = windowsFromJson(value.getJSONArray("scheduleWindows")),
        cooldownEnabled = value.optBoolean("cooldownEnabled", false),
        cooldownSeconds = value.getLong("cooldownSeconds"),
        packageNames = value.getJSONArray("packageNames").strings().toSet(),
    )

    private fun settingsToJson(settings: PortableGlobalSettings): JSONObject = JSONObject()
        .put("exitWarningEnabled", settings.exitWarningEnabled)
        .put("fullScreenExitWarningEnabled", settings.fullScreenExitWarningEnabled)
        .put("exitWarningVibrationEnabled", settings.exitWarningVibrationEnabled)
        .put("usageMilestoneReminderEnabled", settings.usageMilestoneReminderEnabled)
        .put("openUsageTipEnabled", settings.openUsageTipEnabled)
        .put("languageMode", settings.languageMode.name)
        .put("themeMode", settings.themeMode.name)
        .put("themeColor", settings.themeColor.name)
        .put("timeQuotesEnabled", settings.timeQuotesEnabled)
        .put("builtInTimeQuotesEnabled", settings.builtInTimeQuotesEnabled)
        .put("customTimeQuotes", JSONArray(settings.customTimeQuotes))
        .put("automaticUpdateCheckEnabled", settings.automaticUpdateCheckEnabled)
        .put("extensionEnabled", settings.extensionEnabled)
        .put("extensionSeconds", settings.extensionSeconds)
        .put("extensionDailyLimit", settings.extensionDailyLimit)
        .put("extensionSessionLimit", settings.extensionSessionLimit)
        .put("extensionFreeDailyLimit", settings.extensionFreeDailyLimit)
        .put("diagnosticsEnabled", settings.diagnosticsEnabled)
        .put("usageStatsEnabled", settings.usageStatsEnabled)

    private fun settingsFromJson(value: JSONObject) = PortableGlobalSettings(
        exitWarningEnabled = value.optBoolean("exitWarningEnabled", true),
        fullScreenExitWarningEnabled = value.optBoolean("fullScreenExitWarningEnabled", false),
        exitWarningVibrationEnabled = value.optBoolean("exitWarningVibrationEnabled", false),
        usageMilestoneReminderEnabled = value.optBoolean("usageMilestoneReminderEnabled", false),
        openUsageTipEnabled = value.optBoolean("openUsageTipEnabled", true),
        languageMode = enum(value.optString("languageMode"), AppLanguageMode.SYSTEM),
        themeMode = enum(value.optString("themeMode"), AppThemeMode.SYSTEM),
        themeColor = enum(value.optString("themeColor"), AppThemeColor.GREEN),
        timeQuotesEnabled = value.optBoolean("timeQuotesEnabled", true),
        builtInTimeQuotesEnabled = value.optBoolean("builtInTimeQuotesEnabled", true),
        customTimeQuotes = value.optJSONArray("customTimeQuotes")?.strings().orEmpty(),
        automaticUpdateCheckEnabled = value.optBoolean("automaticUpdateCheckEnabled", true),
        extensionEnabled = value.optBoolean("extensionEnabled", true),
        extensionSeconds = value.getLong("extensionSeconds"),
        extensionDailyLimit = value.optInt("extensionDailyLimit", ExtensionQuotaPolicy.DEFAULT_DAILY_LIMIT),
        extensionSessionLimit = value.optInt("extensionSessionLimit", ExtensionQuotaPolicy.DEFAULT_SESSION_LIMIT),
        extensionFreeDailyLimit = value.optInt("extensionFreeDailyLimit", ExtensionQuotaPolicy.DEFAULT_FREE_DAILY_LIMIT),
        diagnosticsEnabled = value.optBoolean("diagnosticsEnabled", true),
        usageStatsEnabled = value.optBoolean("usageStatsEnabled", true),
    )

    private fun windowsToJson(windows: List<com.liuml.apptimelimiter.data.ScheduleWindow>) =
        JSONArray().apply {
            windows.forEach { window ->
                put(
                    JSONObject()
                        .put("days", JSONArray(window.daysOfWeek.sorted()))
                        .put("startMinute", window.startMinute)
                        .put("endMinute", window.endMinute),
                )
            }
        }

    private fun windowsFromJson(values: JSONArray) = values.objects { value ->
        scheduleWindow(
            days = value.getJSONArray("days").ints().toSet(),
            startMinute = value.getInt("startMinute"),
            endMinute = value.getInt("endMinute"),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private inline fun <reified T : Enum<T>> enum(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).map { getString(it) }

    private fun JSONArray.ints(): List<Int> =
        (0 until length()).map { getInt(it) }
}
