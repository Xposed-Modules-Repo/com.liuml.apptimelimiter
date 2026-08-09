package com.liuml.apptimelimiter.core

object TimeQuotePolicy {
    const val MAX_CUSTOM_QUOTES = 20
    const val MAX_QUOTE_CODE_POINTS = 80

    private val BUILT_IN_CHINESE = listOf(
        "时间从不回头，但你可以现在停下。",
        "悬崖勒马，是清醒，不是退缩。",
        "把注意力还给真正重要的事。",
        "片刻停顿，是为了更从容地前行。",
        "今天的节制，是明天的自由。",
        "别让短暂消遣，偷走整段时光。",
    )
    private val BUILT_IN_ENGLISH = listOf(
        "Time never turns back, but you can pause now.",
        "Stopping in time is clarity, not retreat.",
        "Return your attention to what truly matters.",
        "A brief pause can make the next step steadier.",
        "Restraint today creates freedom tomorrow.",
        "Do not let a brief distraction steal a whole stretch of time.",
    )

    fun parseCustomQuotes(raw: String): List<String> =
        sanitize(raw.lineSequence().toList())

    fun sanitize(values: List<String>): List<String> =
        values.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::truncate)
            .distinct()
            .take(MAX_CUSTOM_QUOTES)
            .toList()

    fun encode(values: List<String>): String = sanitize(values).joinToString("\n")

    fun select(
        enabled: Boolean,
        builtInEnabled: Boolean,
        customQuotes: List<String>,
        english: Boolean,
        seed: String,
    ): String? {
        if (!enabled) return null
        val pool = buildList {
            if (builtInEnabled) addAll(if (english) BUILT_IN_ENGLISH else BUILT_IN_CHINESE)
            addAll(sanitize(customQuotes))
        }
        if (pool.isEmpty()) return null
        return pool[Math.floorMod(seed.hashCode(), pool.size)]
    }

    private fun truncate(value: String): String {
        val count = value.codePointCount(0, value.length)
        if (count <= MAX_QUOTE_CODE_POINTS) return value
        return value.substring(0, value.offsetByCodePoints(0, MAX_QUOTE_CODE_POINTS))
    }
}
