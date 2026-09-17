package com.liuml.apptimelimiter.core

/** Extracts bounded error codes/categories, never SDK payloads, URLs or device identifiers. */
object AdFailureDiagnosticPolicy {
    fun signature(code: String?, platformCode: String?, detail: String?): String {
        fun safeCode(value: String?) = value?.trim()?.takeIf { it.matches(Regex("-?[0-9]{1,9}")) }.orEmpty()
        val text = detail.orEmpty().take(16_384)
        val nested = Regex("(?:platformCode|code)\\s*:?\\s*\\[\\s*(-?[0-9]{1,9})\\s*]", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1] }.distinct().take(6).toList()
        val category = when {
            Regex("no[ _-]fill|not filled|no available ad|no offer|无填充|暂无广告", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "no_fill"
            Regex("timeout|timed out", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "timeout"
            Regex("network error|connection failed|UnknownHost|SSLHandshake", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "network"
            else -> "source_error"
        }
        return buildString {
            append(safeCode(code).ifBlank { "unknown" })
            safeCode(platformCode).takeIf { it.isNotEmpty() }?.let { append(":platform_").append(it) }
            if (nested.isNotEmpty()) append(":codes_").append(nested.joinToString("_"))
            append(":category_").append(category)
        }
    }
}
