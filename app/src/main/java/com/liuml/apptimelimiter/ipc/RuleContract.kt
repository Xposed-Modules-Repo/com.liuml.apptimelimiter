package com.liuml.apptimelimiter.ipc

import android.net.Uri

object RuleContract {
    const val AUTHORITY = "com.liuml.apptimelimiter.rules"
    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

    const val METHOD_GET_RULE = "get_rule"
    const val METHOD_APPEND_LOG = "append_log"
    const val METHOD_RECORD_USAGE = "record_usage"

    const val KEY_OK = "ok"
    const val KEY_ENABLED = "enabled"
    const val KEY_LIMIT_SECONDS = "limit_seconds"
    const val KEY_MODE = "mode"
    const val KEY_DAILY_ENABLED = "daily_enabled"
    const val KEY_DAILY_LIMIT_SECONDS = "daily_limit_seconds"
    const val KEY_PER_LAUNCH_ENABLED = "per_launch_enabled"
    const val KEY_PER_LAUNCH_LIMIT_SECONDS = "per_launch_limit_seconds"
    const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    const val KEY_SCHEDULE_MODE = "schedule_mode"
    const val KEY_SCHEDULE_WINDOWS = "schedule_windows"
    const val KEY_COOLDOWN_ENABLED = "cooldown_enabled"
    const val KEY_COOLDOWN_SECONDS = "cooldown_seconds"
    const val KEY_VERSION = "version"
    const val KEY_EXIT_WARNING_ENABLED = "exit_warning_enabled"
    const val KEY_EXTENSION_SECONDS = "extension_seconds"
    const val KEY_DIAGNOSTICS_ENABLED = "diagnostics_enabled"
    const val KEY_USAGE_STATS_ENABLED = "usage_stats_enabled"
    const val KEY_SYSTEM_TODAY_USED_MS = "system_today_used_ms"
    const val KEY_DURATION_MS = "duration_ms"
    const val KEY_DAY_TOKEN = "day_token"
    const val KEY_LAUNCH_INCREMENT = "launch_increment"
    const val KEY_LIMIT_HIT_INCREMENT = "limit_hit_increment"
    const val KEY_HOOK_VERSION_CODE = "hook_version_code"
    const val KEY_LEVEL = "level"
    const val KEY_EVENT = "event"
    const val KEY_MESSAGE = "message"
}
