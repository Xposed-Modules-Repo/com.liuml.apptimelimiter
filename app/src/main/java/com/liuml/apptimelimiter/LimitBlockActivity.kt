package com.liuml.apptimelimiter

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.liuml.apptimelimiter.core.QuotaKind
import com.liuml.apptimelimiter.core.LimitEnforcementPolicy
import com.liuml.apptimelimiter.core.ScheduleConstraint
import com.liuml.apptimelimiter.core.ScheduleEvaluator
import com.liuml.apptimelimiter.data.LimitEnforcementMode
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.ScheduleCodec
import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.ipc.RuleContract
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Optional standalone break page. It intentionally belongs to Time Stop so Android pauses the
 * target Activity. Some vendor ROMs may show an associated-launch confirmation before opening it.
 */
class LimitBlockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var hintView: TextView
    private var targetPackage = ""
    private var initialTitle = ""
    private var initialMessage = ""
    private var initialRuleVersion = Long.MIN_VALUE
    private var initialGroupVersion = Long.MIN_VALUE
    private var cooldownEndsAtMillis = 0L
    private var reachedKinds = emptySet<QuotaKind>()
    private var initialDayToken = ""
    private var english = false
    private var authorized = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                refreshRestriction()
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) {
                // Back is blocked; Home and Recents remain controlled by the system.
            }
        }
        if (!consumeBreakSession(intent)) {
            finishWithoutAnimation()
            return
        }
        authorized = true
        buildContent()
        applyTrustedIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (!consumeBreakSession(intent)) return
        setIntent(intent)
        applyTrustedIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!authorized) return
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    @Deprecated("The break page intentionally blocks Back.")
    override fun onBackPressed() = Unit

    private fun buildContent() {
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            setTextColor(color)
            textSize = size
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(BACKGROUND)
        }
        root.addView(text(32f, Color.WHITE, true).apply { this.text = "T◷" })
        titleView = text(26f, Color.WHITE, true).apply {
            setPadding(0, dp(18), 0, 0)
        }
        messageView = text(16f, SECONDARY_TEXT).apply {
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        hintView = text(13f, HINT_TEXT).apply {
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(titleView)
        root.addView(messageView)
        root.addView(hintView)
        setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun consumeBreakSession(source: android.content.Intent): Boolean {
        val requestedPackage = source.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        val token = source.getStringExtra(EXTRA_BREAK_SESSION_TOKEN).orEmpty()
        if (requestedPackage.isBlank() || token.isBlank()) return false
        val extras = Bundle().apply {
            putString(RuleContract.KEY_BREAK_SESSION_TOKEN, token)
        }
        return runCatching {
            contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_CONSUME_BREAK_SESSION,
                requestedPackage,
                extras,
            )
        }.getOrNull()?.getBoolean(RuleContract.KEY_OK, false) == true
    }

    private fun applyTrustedIntent(source: android.content.Intent) {
        targetPackage = source.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        initialTitle = source.getStringExtra(EXTRA_TITLE).orEmpty()
        initialMessage = source.getStringExtra(EXTRA_MESSAGE).orEmpty()
        initialRuleVersion = source.getLongExtra(EXTRA_RULE_VERSION, Long.MIN_VALUE)
        initialGroupVersion = source.getLongExtra(EXTRA_GROUP_VERSION, Long.MIN_VALUE)
        cooldownEndsAtMillis = source.getLongExtra(EXTRA_COOLDOWN_ENDS_AT, 0L)
        reachedKinds = source.getStringExtra(EXTRA_REACHED_KINDS)
            .orEmpty()
            .split(',')
            .mapNotNull { raw -> runCatching { QuotaKind.valueOf(raw) }.getOrNull() }
            .toSet()
        initialDayToken = source.getStringExtra(EXTRA_DAY_TOKEN).orEmpty()
        english = source.getBooleanExtra(EXTRA_ENGLISH, false)
        hintView.text = if (english) {
            "Background media may continue · Home and Recents remain available"
        } else {
            "后台媒体可能继续播放 · 可使用主页或最近任务离开"
        }
        updateText(initialTitle, initialMessage)
    }

    private fun refreshRestriction() {
        if (targetPackage.isBlank()) {
            finishWithoutAnimation()
            return
        }
        val rule = runCatching {
            contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_GET_RULE,
                targetPackage,
                null,
            )
        }.getOrNull()?.takeIf { it.getBoolean(RuleContract.KEY_OK, false) } ?: return
        if (!rule.getBoolean(RuleContract.KEY_ENABLED, false)) {
            finishWithoutAnimation()
            return
        }
        if (
            LimitEnforcementPolicy.parseMode(
                rule.getString(RuleContract.KEY_LIMIT_ENFORCEMENT_MODE),
            ) != LimitEnforcementMode.EXTERNAL_BREAK_PAGE
        ) {
            finishWithoutAnimation()
            return
        }
        val nowMillis = System.currentTimeMillis()
        val currentRuleVersion = rule.getLong(RuleContract.KEY_VERSION, Long.MIN_VALUE)
        val currentGroupVersion = rule.getLong(RuleContract.KEY_GROUP_VERSION, Long.MIN_VALUE)
        val ruleVersionUnchanged = currentRuleVersion == initialRuleVersion
        val groupVersionUnchanged = currentGroupVersion == initialGroupVersion
        val constraints = buildList {
            if (rule.getBoolean(RuleContract.KEY_SCHEDULE_ENABLED, false)) {
                add(
                    ScheduleConstraint(
                        parseMode(rule.getString(RuleContract.KEY_SCHEDULE_MODE)),
                        ScheduleCodec.decode(rule.getString(RuleContract.KEY_SCHEDULE_WINDOWS)),
                    ),
                )
            }
            if (rule.getBoolean(RuleContract.KEY_GROUP_SCHEDULE_ENABLED, false)) {
                add(
                    ScheduleConstraint(
                        parseMode(rule.getString(RuleContract.KEY_GROUP_SCHEDULE_MODE)),
                        ScheduleCodec.decode(
                            rule.getString(RuleContract.KEY_GROUP_SCHEDULE_WINDOWS),
                        ),
                    ),
                )
            }
        }
        val scheduleDecision = ScheduleEvaluator.evaluateAll(
            constraints,
            ZonedDateTime.now(),
        )
        val scheduleBlocked = constraints.isNotEmpty() && !scheduleDecision.allowed
        val sharedCooldownEnd = rule.getLong(
            RuleContract.KEY_GROUP_COOLDOWN_ENDS_AT_MS,
            0L,
        )
        val configuredCooldown = rule.getBoolean(RuleContract.KEY_COOLDOWN_ENABLED, false) ||
            rule.getBoolean(RuleContract.KEY_GROUP_COOLDOWN_ENABLED, false)
        val passedCooldownEnd = cooldownEndsAtMillis.takeIf {
            configuredCooldown && ruleVersionUnchanged && groupVersionUnchanged
        } ?: 0L
        val effectiveCooldownEnd = maxOf(sharedCooldownEnd, passedCooldownEnd)
        val cooldownActive = effectiveCooldownEnd > nowMillis
        val sameDay = initialDayToken == LocalDate.now().toString()
        val groupDailyReached = dailyQuotaReached(
            enabled = rule.getBoolean(RuleContract.KEY_GROUP_DAILY_ENABLED, false),
            usedMillis = rule.getLong(RuleContract.KEY_GROUP_TODAY_USED_MS, -1L),
            limitSeconds = rule.getLong(
                RuleContract.KEY_GROUP_DAILY_LIMIT_SECONDS,
                RuleRepository.DEFAULT_GROUP_LIMIT_SECONDS,
            ),
        ) || (
            QuotaKind.GROUP_DAILY in reachedKinds &&
                sameDay &&
                groupVersionUnchanged
            )
        val appDailyReached = dailyQuotaReached(
            enabled = rule.getBoolean(RuleContract.KEY_DAILY_ENABLED, false),
            usedMillis = rule.getLong(RuleContract.KEY_SYSTEM_TODAY_USED_MS, -1L),
            limitSeconds = rule.getLong(
                RuleContract.KEY_DAILY_LIMIT_SECONDS,
                RuleRepository.DEFAULT_LIMIT_SECONDS,
            ),
        ) || (
            QuotaKind.APP_DAILY in reachedKinds &&
                sameDay &&
                ruleVersionUnchanged
            )
        val groupPerReached = QuotaKind.GROUP_PER_LAUNCH in reachedKinds &&
            groupVersionUnchanged
        val appPerReached = QuotaKind.APP_PER_LAUNCH in reachedKinds &&
            ruleVersionUnchanged
        when {
            scheduleBlocked -> updateText(
                if (english) "Unavailable at this time" else "当前时段不可使用",
                scheduleDecision.nextTransition?.format(
                    if (english) {
                        DateTimeFormatter.ofPattern("MMM d, E HH:mm", Locale.ENGLISH)
                    } else {
                        DateTimeFormatter.ofPattern("M月d日 E HH:mm", Locale.CHINA)
                    },
                )?.let { nextAllowed ->
                    if (english) {
                        "Next available: $nextAllowed"
                    } else {
                        "下次可用：$nextAllowed"
                    }
                } ?: if (english) {
                    "Wait until the configured available period."
                } else {
                    "请等待允许使用时段"
                },
            )
            cooldownActive -> {
                val seconds =
                    ((effectiveCooldownEnd - nowMillis + 999L) / 1000L).coerceAtLeast(1L)
                updateText(
                    if (english) "Take a break" else "休息一下",
                    if (english) {
                        "Continue automatically in ${formatRemaining(seconds)}"
                    } else {
                        "${formatRemaining(seconds)}后自动继续"
                    },
                )
            }
            groupDailyReached -> updateText(
                if (english) "Group allowance exhausted" else "今日分组额度已耗尽",
                if (english) "Available again after the daily reset." else "每日额度重置后可再次使用",
            )
            appDailyReached -> updateText(
                if (english) "Daily allowance exhausted" else "今日使用额度已耗尽",
                if (english) "Available again after the daily reset." else "每日额度重置后可再次使用",
            )
            (groupPerReached || appPerReached) && effectiveCooldownEnd <= 0L -> updateText(
                if (english) "Session allowance exhausted" else "本次使用额度已耗尽",
                if (english) {
                    "No cooldown is configured. End this app session to start again."
                } else {
                    "未设置冷却，请结束当前应用进程后重新计时"
                },
            )
            else -> finishWithoutAnimation()
        }
    }

    private fun dailyQuotaReached(
        enabled: Boolean,
        usedMillis: Long,
        limitSeconds: Long,
    ): Boolean {
        if (!enabled || usedMillis < 0L) return false
        val safeSeconds = limitSeconds.coerceIn(
            RuleRepository.MIN_LIMIT_SECONDS,
            RuleRepository.MAX_LIMIT_SECONDS,
        )
        return usedMillis >= safeSeconds * 1000L
    }

    private fun updateText(title: String, message: String) {
        titleView.text = title
        messageView.text = message
        window.decorView.contentDescription = "$title，$message"
    }

    private fun formatRemaining(totalSeconds: Long): String {
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (english) {
            buildList {
                if (hours > 0L) add("$hours hr")
                if (minutes > 0L) add("$minutes min")
                if (seconds > 0L || isEmpty()) add("$seconds sec")
            }.joinToString(" ")
        } else {
            buildString {
                if (hours > 0L) append(hours).append("小时")
                if (minutes > 0L) append(minutes).append("分")
                if (seconds > 0L || isEmpty()) append(seconds).append("秒")
            }
        }
    }

    private fun finishWithoutAnimation() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun parseMode(raw: String?): ScheduleMode =
        raw?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
            ?: ScheduleMode.BLOCK_DURING

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_BREAK_SESSION_TOKEN = "break_session_token"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_RULE_VERSION = "rule_version"
        const val EXTRA_GROUP_VERSION = "group_version"
        const val EXTRA_COOLDOWN_ENDS_AT = "cooldown_ends_at"
        const val EXTRA_REACHED_KINDS = "reached_kinds"
        const val EXTRA_DAY_TOKEN = "day_token"
        const val EXTRA_ENGLISH = "english"
        private const val REFRESH_INTERVAL_MS = 1_000L
        private const val BACKGROUND = 0xFF090909.toInt()
        private const val SECONDARY_TEXT = 0xD9FFFFFF.toInt()
        private const val HINT_TEXT = 0x9FFFFFFF.toInt()
    }
}
