package com.liuml.apptimelimiter

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.liuml.apptimelimiter.core.BreakSessionPolicy
import com.liuml.apptimelimiter.core.QuotaKind
import com.liuml.apptimelimiter.core.LimitEnforcementPolicy
import com.liuml.apptimelimiter.core.ScheduleConstraint
import com.liuml.apptimelimiter.core.ScheduleEvaluator
import com.liuml.apptimelimiter.core.TimeQuotePolicy
import com.liuml.apptimelimiter.data.GlobalSettings
import com.liuml.apptimelimiter.data.LimitEnforcementMode
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.ScheduleCodec
import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.ipc.RuleContract
import com.liuml.apptimelimiter.nonroot.NonRootProtectionStatusRepository
import com.liuml.apptimelimiter.nonroot.NonRootRuntimeStore
import com.liuml.apptimelimiter.nonroot.TimeStopAccessibilityService
import com.liuml.apptimelimiter.statistics.DeviceUsageStatsRepository
import com.liuml.apptimelimiter.ui.TargetUiPalette
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
    private lateinit var quoteView: TextView
    private lateinit var hintView: TextView
    private lateinit var exitView: TextView
    private var targetPackage = ""
    private var launchAttemptId = ""
    private var confirmedAttemptId = ""
    private var initialRuleVersion = Long.MIN_VALUE
    private var initialGroupVersion = Long.MIN_VALUE
    private var cooldownEndsAtMillis = 0L
    private var sessionResetAtMillis = 0L
    private var reachedKinds = emptySet<QuotaKind>()
    private var initialDayToken = ""
    private var english = false
    private var authorized = false
    private var nonRoot = false
    private var ruleReadFailures = 0
    private var lastRestrictionState = ""
    private var appliedThemeKey = ""
    private val deviceUsageStatsRepository by lazy {
        DeviceUsageStatsRepository(applicationContext)
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) {
                // Back is blocked; Home and Recents remain controlled by the system.
            }
        }
        val restored = restoreTrustedState(savedInstanceState)
        if (!restored && !consumeBreakSession(intent)) {
            diagnostic(
                "WARN",
                intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty(),
                "BREAK_PAGE_TOKEN_REJECTED",
                "source=onCreate, nonRoot=${intent.getBooleanExtra(EXTRA_NON_ROOT, false)}",
            )
            finishWithoutAnimation("token_rejected")
            return
        }
        authorized = true
        buildContent()
        if (restored) {
            applyPageCopy(RuleRepository(this).getGlobalSettings())
            showConfirmingCopy()
        } else {
            applyTrustedIntent(intent)
        }
        diagnostic(
            "INFO",
            targetPackage,
            if (restored) "BREAK_PAGE_ACTIVITY_RECREATED" else "BREAK_PAGE_ACTIVITY_CREATED",
            "attempt=${launchAttemptId.take(12)}, nonRoot=$nonRoot, restored=$restored",
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!BreakSessionPolicy.mayRestoreAuthorizedActivity(authorized, targetPackage)) return
        outState.putBoolean(STATE_AUTHORIZED, true)
        outState.putString(STATE_TARGET_PACKAGE, targetPackage)
        outState.putString(STATE_LAUNCH_ATTEMPT_ID, launchAttemptId)
        outState.putString(STATE_CONFIRMED_ATTEMPT_ID, confirmedAttemptId)
        outState.putLong(STATE_RULE_VERSION, initialRuleVersion)
        outState.putLong(STATE_GROUP_VERSION, initialGroupVersion)
        outState.putLong(STATE_COOLDOWN_ENDS_AT, cooldownEndsAtMillis)
        outState.putLong(STATE_SESSION_RESET_AT, sessionResetAtMillis)
        outState.putString(STATE_REACHED_KINDS, reachedKinds.joinToString(",") { it.name })
        outState.putString(STATE_DAY_TOKEN, initialDayToken)
        outState.putBoolean(STATE_ENGLISH, english)
        outState.putBoolean(STATE_NON_ROOT, nonRoot)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (!consumeBreakSession(intent)) {
            diagnostic(
                "WARN",
                intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty(),
                "BREAK_PAGE_TOKEN_REJECTED",
                "source=onNewIntent, nonRoot=${intent.getBooleanExtra(EXTRA_NON_ROOT, false)}",
            )
            return
        }
        setIntent(intent)
        ensureCurrentTheme()
        applyTrustedIntent(intent)
        diagnostic(
            "INFO",
            targetPackage,
            "BREAK_PAGE_ACTIVITY_NEW_INTENT",
            "attempt=${launchAttemptId.take(12)}, nonRoot=$nonRoot",
        )
        handler.post { confirmNonRootForegroundIfReady() }
    }

    override fun onResume() {
        super.onResume()
        if (!authorized) return
        activeRestrictionTarget = targetPackage
        ensureCurrentTheme()
        diagnostic(
            "INFO",
            targetPackage,
            "BREAK_PAGE_ACTIVITY_RESUMED",
            "attempt=${launchAttemptId.take(12)}, hasFocus=${hasWindowFocus()}",
        )
        handler.post { confirmNonRootForegroundIfReady() }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        clearActiveRestrictionTarget()
        if (authorized) {
            diagnostic(
                "INFO",
                targetPackage,
                "BREAK_PAGE_ACTIVITY_PAUSED",
                "attempt=${launchAttemptId.take(12)}, finishing=$isFinishing",
            )
        }
        super.onPause()
    }

    override fun onDestroy() {
        clearActiveRestrictionTarget()
        if (authorized && nonRoot && isFinishing) {
            TimeStopAccessibilityService.notifyRestrictionPageClosed(targetPackage)
        }
        super.onDestroy()
    }

    private fun clearActiveRestrictionTarget() {
        if (activeRestrictionTarget == targetPackage) activeRestrictionTarget = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) confirmNonRootForegroundIfReady()
    }

    private fun confirmNonRootForegroundIfReady() {
        if (
            !hasWindowFocus() ||
            !authorized ||
            !nonRoot ||
            launchAttemptId.isBlank() ||
            confirmedAttemptId == launchAttemptId
        ) return
        confirmedAttemptId = launchAttemptId
        diagnostic(
            "INFO",
            targetPackage,
            "BREAK_PAGE_ACTIVITY_FOCUSED",
            "attempt=${launchAttemptId.take(12)}",
        )
        if (
            !TimeStopAccessibilityService.notifyRestrictionPageShown(
                targetPackage,
                launchAttemptId,
            )
        ) {
            diagnostic(
                "WARN",
                targetPackage,
                "BREAK_PAGE_ACTIVITY_REJECTED",
                "attempt=${launchAttemptId.take(12)}, reason=stale_or_cancelled",
            )
            finishWithoutAnimation("stale_non_root_attempt")
        }
    }

    @Deprecated("The break page intentionally blocks Back.")
    override fun onBackPressed() = Unit

    private fun buildContent(
        settings: GlobalSettings = RuleRepository(this).getGlobalSettings(),
    ) {
        val colors = TargetUiPalette.resolve(
            this,
            settings.themeMode,
            settings.themeColor,
        )
        appliedThemeKey = "${settings.themeMode}/${settings.themeColor}/${colors.isDark}"
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !colors.isDark
            isAppearanceLightNavigationBars = !colors.isDark
        }
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
            setBackgroundColor(colors.background)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(30), dp(28), dp(26))
            background = GradientDrawable().apply {
                setColor(colors.surface)
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), colors.outline)
            }
        }
        card.addView(text(12f, colors.primary, true).apply {
            this.text = "TIME STOP"
            letterSpacing = 0.14f
        })
        card.addView(text(36f, colors.primary, true).apply {
            this.text = "T◷"
            setPadding(0, dp(12), 0, 0)
        })
        titleView = text(26f, colors.textPrimary, true).apply {
            setPadding(0, dp(16), 0, 0)
        }
        messageView = text(16f, colors.textSecondary).apply {
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        quoteView = text(13f, colors.textSecondary).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setLineSpacing(0f, 1.12f)
            maxLines = 2
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(colors.surfaceContainer)
                cornerRadius = dp(16).toFloat()
            }
        }
        hintView = text(13f, colors.textSecondary).apply {
            setPadding(0, dp(24), 0, 0)
        }
        exitView = text(15f, colors.onPrimary, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(50)
            setPadding(dp(28), dp(10), dp(28), dp(10))
            background = GradientDrawable().apply {
                setColor(colors.primary)
                cornerRadius = dp(17).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { leaveToHome() }
        }
        card.addView(titleView)
        card.addView(messageView)
        card.addView(
            quoteView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        card.addView(hintView)
        card.addView(
            exitView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            ).apply { topMargin = dp(24) },
        )
        root.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { width = resources.displayMetrics.widthPixels.coerceAtMost(dp(460)) },
        )
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
        launchAttemptId = source.getStringExtra(EXTRA_LAUNCH_ATTEMPT_ID).orEmpty()
        lastRestrictionState = ""
        initialRuleVersion = source.getLongExtra(EXTRA_RULE_VERSION, Long.MIN_VALUE)
        initialGroupVersion = source.getLongExtra(EXTRA_GROUP_VERSION, Long.MIN_VALUE)
        cooldownEndsAtMillis = source.getLongExtra(EXTRA_COOLDOWN_ENDS_AT, 0L)
        sessionResetAtMillis = source.getLongExtra(EXTRA_SESSION_RESET_AT, 0L)
        reachedKinds = source.getStringExtra(EXTRA_REACHED_KINDS)
            .orEmpty()
            .split(',')
            .mapNotNull { raw -> runCatching { QuotaKind.valueOf(raw) }.getOrNull() }
            .toSet()
        initialDayToken = source.getStringExtra(EXTRA_DAY_TOKEN).orEmpty()
        english = source.getBooleanExtra(EXTRA_ENGLISH, false)
        nonRoot = source.getBooleanExtra(EXTRA_NON_ROOT, false)
        val settings = RuleRepository(this).getGlobalSettings()
        applyPageCopy(settings)
        // The target process can request a token for its own configured package. Never display
        // caller-provided copy, even with a valid token; all restriction text is derived from the
        // Provider snapshot below.
        showConfirmingCopy()
    }

    private fun restoreTrustedState(state: Bundle?): Boolean {
        if (state == null) return false
        val restoredTarget = state.getString(STATE_TARGET_PACKAGE).orEmpty()
        if (
            !BreakSessionPolicy.mayRestoreAuthorizedActivity(
                savedAuthorized = state.getBoolean(STATE_AUTHORIZED, false),
                targetPackage = restoredTarget,
            )
        ) return false
        targetPackage = restoredTarget
        launchAttemptId = state.getString(STATE_LAUNCH_ATTEMPT_ID).orEmpty()
        confirmedAttemptId = state.getString(STATE_CONFIRMED_ATTEMPT_ID).orEmpty()
        initialRuleVersion = state.getLong(STATE_RULE_VERSION, Long.MIN_VALUE)
        initialGroupVersion = state.getLong(STATE_GROUP_VERSION, Long.MIN_VALUE)
        cooldownEndsAtMillis = state.getLong(STATE_COOLDOWN_ENDS_AT, 0L)
        sessionResetAtMillis = state.getLong(STATE_SESSION_RESET_AT, 0L)
        reachedKinds = state.getString(STATE_REACHED_KINDS)
            .orEmpty()
            .split(',')
            .mapNotNull { raw -> runCatching { QuotaKind.valueOf(raw) }.getOrNull() }
            .toSet()
        initialDayToken = state.getString(STATE_DAY_TOKEN).orEmpty()
        english = state.getBoolean(STATE_ENGLISH, false)
        nonRoot = state.getBoolean(STATE_NON_ROOT, false)
        lastRestrictionState = ""
        return true
    }

    private fun showConfirmingCopy() {
        updateText(
            if (english) "Confirming restriction" else "正在确认限制状态",
            if (english) "Reading the current Time Stop rule…" else "正在读取时停中的当前规则…",
        )
    }

    private fun ensureCurrentTheme() {
        val settings = RuleRepository(this).getGlobalSettings()
        val colors = TargetUiPalette.resolve(this, settings.themeMode, settings.themeColor)
        val currentKey = "${settings.themeMode}/${settings.themeColor}/${colors.isDark}"
        if (currentKey == appliedThemeKey) return
        buildContent(settings)
        if (targetPackage.isNotBlank()) applyPageCopy(settings)
    }

    private fun applyPageCopy(settings: GlobalSettings) {
        val quote = TimeQuotePolicy.select(
            enabled = settings.timeQuotesEnabled,
            builtInEnabled = settings.builtInTimeQuotesEnabled,
            customQuotes = settings.customTimeQuotes,
            english = english,
            seed = "break:$targetPackage:$launchAttemptId:$initialDayToken",
        )
        quoteView.text = quote?.let { "“$it”" }.orEmpty()
        quoteView.visibility = if (quote.isNullOrBlank()) View.GONE else View.VISIBLE
        hintView.text = if (english) {
            "Background media may continue · Home and Recents remain available"
        } else {
            "后台媒体可能继续播放 · 可使用主页或最近任务离开"
        }
        exitView.text = if (english) "Exit app" else "退出应用"
    }

    private fun refreshRestriction() {
        if (targetPackage.isBlank()) {
            finishWithoutAnimation("blank_target")
            return
        }
        if (
            nonRoot &&
            (
                !RuleRepository(this).getGlobalSettings().protectionMode.usesNonRoot ||
                    !NonRootProtectionStatusRepository.isAccessibilityEnabled(this) ||
                    !deviceUsageStatsRepository.hasUsageAccess()
                )
        ) {
            finishWithoutAnimation("non_root_prerequisite_lost")
            return
        }
        val ruleRequestExtras = if (nonRoot) {
            Bundle().apply {
                putBoolean(RuleContract.KEY_REQUEST_NON_ROOT_RULE_SNAPSHOT, true)
            }
        } else {
            null
        }
        val rule = runCatching {
            contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_GET_RULE,
                targetPackage,
                ruleRequestExtras,
            )
        }.getOrNull()?.takeIf { it.getBoolean(RuleContract.KEY_OK, false) }
        if (rule == null) {
            ruleReadFailures++
            diagnostic(
                "WARN",
                targetPackage,
                "BREAK_PAGE_RULE_READ_FAILED",
                "attempt=$ruleReadFailures/${BreakSessionPolicy.MAX_RULE_READ_FAILURES}",
            )
            if (BreakSessionPolicy.shouldFailClosedAfterRuleReadFailure(ruleReadFailures)) {
                leaveToHomeAndFinish("rule_read_failed")
            }
            return
        }
        ruleReadFailures = 0
        if (!rule.getBoolean(RuleContract.KEY_ENABLED, false)) {
            finishWithoutAnimation("rule_disabled")
            return
        }
        if (
            !nonRoot &&
            LimitEnforcementPolicy.parseMode(
                rule.getString(RuleContract.KEY_LIMIT_ENFORCEMENT_MODE),
            ) != LimitEnforcementMode.EXTERNAL_BREAK_PAGE
        ) {
            finishWithoutAnimation("enforcement_mode_changed")
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
            scheduleBlocked -> updateRestrictionState(
                "SCHEDULE",
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
                updateRestrictionState(
                    "COOLDOWN:$effectiveCooldownEnd",
                    if (english) "Take a break" else "休息一下",
                    if (english) {
                        "Continue automatically in ${formatRemaining(seconds)}"
                    } else {
                        "${formatRemaining(seconds)}后自动继续"
                    },
                )
            }
            groupDailyReached -> updateRestrictionState(
                "GROUP_DAILY:${LocalDate.now()}",
                if (english) "Group allowance exhausted" else "今日分组额度已耗尽",
                if (english) "Available again after the daily reset." else "每日额度重置后可再次使用",
            )
            appDailyReached -> updateRestrictionState(
                "APP_DAILY:${LocalDate.now()}",
                if (english) "Daily allowance exhausted" else "今日使用额度已耗尽",
                if (english) "Available again after the daily reset." else "每日额度重置后可再次使用",
            )
            nonRoot && (groupPerReached || appPerReached) &&
                sessionResetAtMillis > nowMillis -> {
                val seconds =
                    ((sessionResetAtMillis - nowMillis + 999L) / 1000L).coerceAtLeast(1L)
                updateRestrictionState(
                    "SESSION_GRACE:$sessionResetAtMillis",
                    if (english) "Take a break" else "休息一下",
                    if (english) {
                        "A new session starts in ${formatRemaining(seconds)}"
                    } else {
                        "${formatRemaining(seconds)}后开始新的使用会话"
                    },
                )
            }
            nonRoot && (groupPerReached || appPerReached) &&
                sessionResetAtMillis > 0L -> {
                NonRootRuntimeStore(this).clearSession(targetPackage)
                finishWithoutAnimation("single_session_reset")
            }
            (groupPerReached || appPerReached) && effectiveCooldownEnd <= 0L ->
                updateRestrictionState(
                "PER_SESSION",
                if (english) "Session allowance exhausted" else "本次使用额度已耗尽",
                if (english) {
                    "End this app process to start a new session."
                } else {
                    "结束当前应用进程后可开始新的使用会话"
                },
                )
            else -> finishWithoutAnimation("restriction_cleared")
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

    private fun updateRestrictionState(
        state: String,
        title: String,
        message: String,
    ) {
        if (state != lastRestrictionState) {
            lastRestrictionState = state
            diagnostic(
                "INFO",
                targetPackage,
                "BREAK_PAGE_RESTRICTION_STATE",
                "state=$state",
            )
        }
        updateText(title, message)
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

    private fun finishWithoutAnimation(reason: String) {
        diagnostic(
            "INFO",
            targetPackage,
            "BREAK_PAGE_ACTIVITY_FINISHING",
            "reason=$reason, attempt=${launchAttemptId.take(12)}, nonRoot=$nonRoot",
        )
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun leaveToHome() {
        diagnostic(
            "INFO",
            targetPackage,
            "USER_EXIT_REQUESTED",
            "source=break_page, nonRoot=$nonRoot",
        )
        launchHome()
    }

    private fun leaveToHomeAndFinish(reason: String) {
        diagnostic(
            "WARN",
            targetPackage,
            "BREAK_PAGE_FAIL_CLOSED",
            "reason=$reason, nonRoot=$nonRoot",
        )
        launchHome()
        finishWithoutAnimation(reason)
    }

    private fun launchHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(home) }
            .onFailure {
                diagnostic(
                    "WARN",
                    targetPackage,
                    "BREAK_PAGE_EXIT_HOME_FAILED",
                    it.toString(),
                )
                moveTaskToBack(true)
            }
    }

    private fun parseMode(raw: String?): ScheduleMode =
        raw?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
            ?: ScheduleMode.BLOCK_DURING

    companion object {
        @Volatile
        private var activeRestrictionTarget: String? = null

        fun isRestrictionPageForegroundFor(packageName: String): Boolean =
            packageName.isNotBlank() && activeRestrictionTarget == packageName

        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_BREAK_SESSION_TOKEN = "break_session_token"
        const val EXTRA_LAUNCH_ATTEMPT_ID = "launch_attempt_id"
        const val EXTRA_RULE_VERSION = "rule_version"
        const val EXTRA_GROUP_VERSION = "group_version"
        const val EXTRA_COOLDOWN_ENDS_AT = "cooldown_ends_at"
        const val EXTRA_SESSION_RESET_AT = "session_reset_at"
        const val EXTRA_REACHED_KINDS = "reached_kinds"
        const val EXTRA_DAY_TOKEN = "day_token"
        const val EXTRA_ENGLISH = "english"
        const val EXTRA_NON_ROOT = "non_root"
        private const val STATE_AUTHORIZED = "state_authorized"
        private const val STATE_TARGET_PACKAGE = "state_target_package"
        private const val STATE_LAUNCH_ATTEMPT_ID = "state_launch_attempt_id"
        private const val STATE_CONFIRMED_ATTEMPT_ID = "state_confirmed_attempt_id"
        private const val STATE_RULE_VERSION = "state_rule_version"
        private const val STATE_GROUP_VERSION = "state_group_version"
        private const val STATE_COOLDOWN_ENDS_AT = "state_cooldown_ends_at"
        private const val STATE_SESSION_RESET_AT = "state_session_reset_at"
        private const val STATE_REACHED_KINDS = "state_reached_kinds"
        private const val STATE_DAY_TOKEN = "state_day_token"
        private const val STATE_ENGLISH = "state_english"
        private const val STATE_NON_ROOT = "state_non_root"
        private const val REFRESH_INTERVAL_MS = 1_000L
    }

    private fun diagnostic(
        level: String,
        packageName: String,
        event: String,
        message: String,
    ) {
        if (!RuleRepository(this).getGlobalSettings().diagnosticsEnabled) return
        DiagnosticsRepository(this).append(
            level = level,
            packageName = packageName.ifBlank { this.packageName },
            event = event,
            message = message,
        )
    }
}
