package com.liuml.apptimelimiter

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Toast
import androidx.core.view.WindowCompat
import com.liuml.apptimelimiter.core.BreakSessionPolicy
import com.liuml.apptimelimiter.core.QuotaKind
import com.liuml.apptimelimiter.core.RestrictionPagePresentationPolicy
import com.liuml.apptimelimiter.core.LimitEnforcementPolicy
import com.liuml.apptimelimiter.core.RuleDecisionSnapshot
import com.liuml.apptimelimiter.core.RuleUsageMeasurement
import com.liuml.apptimelimiter.core.RuleAvailabilityPolicy
import com.liuml.apptimelimiter.core.RestrictionReason
import com.liuml.apptimelimiter.core.RestrictionReasonText
import com.liuml.apptimelimiter.core.ParentOverrideDurationPolicy
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
import com.liuml.apptimelimiter.security.ChildLockRepository
import com.liuml.apptimelimiter.statistics.DeviceUsageStatsRepository
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import com.liuml.apptimelimiter.ui.TargetUiPalette
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.lang.ref.WeakReference

/**
 * Optional standalone break page. It intentionally belongs to Time Stop so Android pauses the
 * target Activity. Some vendor ROMs may show an associated-launch confirmation before opening it.
 */
class LimitBlockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var restrictionDetailsView: TextView
    private var restrictionDetailsExpanded = false
    private var restrictionSnapshot: RuleDecisionSnapshot? = null
    private var restrictionNextAvailable: ZonedDateTime? = null
    private lateinit var quoteView: TextView
    private lateinit var hintView: TextView
    private lateinit var temporaryAccessTitleView: TextView
    private lateinit var actionFeedbackView: TextView
    private lateinit var exitView: TextView
    private lateinit var extensionView: TextView
    private var extensionRequestId = UUID.randomUUID().toString()
    private lateinit var parentUnlockView: TextView
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
    private var adOnly = false
    private var restrictionIncidentId = ""
    private var controlSessionId = ""
    private var ruleReadFailures = 0
    private var lastRestrictionState = ""
    private var appliedThemeKey = ""
    private var parentAuthInFlight = false
    private var parentAuthToken = ""
    private var parentAuthPoll: Runnable? = null
    private var runtimeIdentity: Bundle? = null
    private var runtimeRenewedAt = 0L
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
        extensionRequestId = savedInstanceState?.getString("free_extension_request_id") ?: extensionRequestId
        runtimeIdentity = savedInstanceState?.getBundle("control_runtime_identity")
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
        if (!claimActivePage()) {
            diagnostic(
                "INFO",
                intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty(),
                "BREAK_PAGE_DUPLICATE_SUPPRESSED",
                "source=onCreate",
            )
            finishWithoutAnimation("duplicate_break_page")
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
        if (!claimRestrictionUi()) {
            diagnostic("INFO", targetPackage, "BREAK_PAGE_DUPLICATE_SUPPRESSED", "source=provider_claim")
            finishWithoutAnimation("duplicate_restriction_ui")
            return
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
        outState.putString("free_extension_request_id", extensionRequestId)
        outState.putBundle("control_runtime_identity", runtimeIdentity)
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
        outState.putBoolean(STATE_AD_ONLY, adOnly)
        outState.putString(STATE_INCIDENT_ID, restrictionIncidentId)
        outState.putString(STATE_CONTROL_SESSION_ID, controlSessionId)
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
        val incomingPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        val incomingSessionId = intent.getStringExtra(EXTRA_CONTROL_SESSION_ID).orEmpty()
        val incomingIncidentId = intent.getStringExtra(EXTRA_INCIDENT_ID)
            .orEmpty()
            .ifBlank {
                intent.getStringExtra(EXTRA_LAUNCH_ATTEMPT_ID)
                    .orEmpty()
                    .ifBlank { "break:$incomingPackage:$incomingSessionId" }
            }
        if (
            incomingPackage != targetPackage || incomingSessionId != controlSessionId ||
            !claimRestrictionUi(incomingPackage, incomingIncidentId)
        ) {
            diagnostic(
                "INFO",
                incomingPackage,
                "BREAK_PAGE_DUPLICATE_SUPPRESSED",
                "source=onNewIntent active=${restrictionIncidentId.take(48)} incoming=${incomingIncidentId.take(48)}",
            )
            return
        }
        setIntent(intent)
        ensureCurrentTheme()
        val ownedIncident = restrictionIncidentId
        applyTrustedIntent(intent)
        restrictionIncidentId = ownedIncident
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
        handler.removeCallbacksAndMessages(null)
        parentAuthPoll = null
        clearActiveRestrictionTarget()
        releaseActivePage()
        if (isFinishing) releaseRestrictionUi()
        if (authorized && nonRoot && isFinishing) {
            TimeStopAccessibilityService.notifyRestrictionPageClosed(targetPackage)
        }
        super.onDestroy()
    }

    private fun clearActiveRestrictionTarget() {
        if (activeRestrictionTarget == targetPackage) activeRestrictionTarget = null
    }

    private fun claimActivePage(): Boolean = synchronized(ACTIVE_PAGE_LOCK) {
        val existing = activePage?.get()
        if (existing != null && existing !== this && !existing.isFinishing && !existing.isDestroyed) {
            return false
        }
        activePage = WeakReference(this)
        true
    }

    private fun releaseActivePage() = synchronized(ACTIVE_PAGE_LOCK) {
        if (activePage?.get() === this) activePage = null
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
                cornerRadius = dp(24).toFloat()
            }
        }
        card.addView(text(12f, colors.primary, true).apply {
            this.text = "TIME STOP"
            letterSpacing = 0.14f
        })
        card.addView(android.widget.ImageView(this).apply {
            setImageDrawable(com.liuml.apptimelimiter.ui.FunctionIconDrawable("shield", colors.primary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(16) })
        titleView = text(26f, colors.textPrimary, true).apply {
            setPadding(0, dp(16), 0, 0)
        }
        messageView = text(16f, colors.textSecondary).apply {
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        restrictionDetailsView = text(14f, colors.textSecondary).apply {
            setPadding(0, dp(16), 0, dp(12))
            setLineSpacing(0f, 1.15f)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener {
                restrictionDetailsExpanded = !restrictionDetailsExpanded
                renderRestrictionDetails()
            }
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
        temporaryAccessTitleView = text(13f, colors.textSecondary, true).apply {
            setPadding(0, dp(18), 0, dp(2))
            visibility = View.GONE
        }
        actionFeedbackView = text(13f, colors.textSecondary).apply {
            setPadding(dp(2), dp(8), dp(2), 0)
            visibility = View.GONE
        }
        exitView = text(15f, colors.onPrimary, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(50)
            setPadding(dp(28), dp(10), dp(28), dp(10))
            background = GradientDrawable().apply {
                setColor(colors.primary)
                cornerRadius = dp(14).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { leaveToHome() }
        }
        parentUnlockView = text(15f, colors.primary, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(50)
            setPadding(dp(28), dp(10), dp(28), dp(10))
            background = GradientDrawable().apply {
                setColor(colors.primaryContainer)
                cornerRadius = dp(14).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { startParentUnlock() }
        }
        extensionView = text(16f, colors.primary, true).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            text = if (english) "Extend use" else "延长使用"
            setOnClickListener { requestFreeExtension() }
        }
        card.addView(titleView)
        card.addView(messageView)
        card.addView(restrictionDetailsView)
        card.addView(
            quoteView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        card.addView(hintView)
        card.addView(temporaryAccessTitleView)
        card.addView(
            parentUnlockView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            ).apply { topMargin = dp(10) },
        )
        card.addView(extensionView)
        card.addView(actionFeedbackView)
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
        adOnly = source.getBooleanExtra(EXTRA_AD_ONLY, false)
        controlSessionId = source.getStringExtra(EXTRA_CONTROL_SESSION_ID).orEmpty()
        restrictionIncidentId = source.getStringExtra(EXTRA_INCIDENT_ID)
            .orEmpty()
            .ifBlank { launchAttemptId.ifBlank { "break:$targetPackage:$controlSessionId" } }
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
        adOnly = state.getBoolean(STATE_AD_ONLY, false)
        restrictionIncidentId = state.getString(STATE_INCIDENT_ID).orEmpty()
        controlSessionId = state.getString(STATE_CONTROL_SESSION_ID).orEmpty()
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
        val baseHint = if (english) {
            "Background media may continue · Home and Recents remain available"
        } else {
            "后台媒体可能继续播放 · 可使用主页或最近任务离开"
        }
        val privatePinReady = ChildLockRepository(this).isEnabled()
        val sessionReady = controlSessionId.isNotBlank()
        hintView.text = when {
            settings.childLockEnabled && !privatePinReady -> if (english) {
                "$baseHint\nControl Lock PIN data is unavailable. Open Time Stop and set the PIN again."
            } else {
                "$baseHint\n管控锁 PIN 数据不可用，请打开时停重新设置 PIN"
            }
            settings.childLockEnabled && !sessionReady -> if (english) {
                "$baseHint\nThe target app is still using an older Hook. Force stop and reopen it to use PIN unlock."
            } else {
                "$baseHint\n目标应用仍在运行旧版 Hook，强停并重新打开后才能使用 PIN 解锁"
            }
            else -> baseHint
        }
        exitView.text = if (english) "Exit app" else "退出应用"
        parentUnlockView.text = if (english) "Temporary PIN access" else "PIN 临时放行"
        parentUnlockView.visibility = if (
            settings.childLockEnabled && privatePinReady && sessionReady
        ) View.VISIBLE else View.GONE
        updateTemporaryAccessSection()
    }

    private fun refreshRestriction() {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (!parentAuthInFlight && true && nowElapsed - runtimeRenewedAt >= 30_000L) {
            if (!claimRestrictionUi()) {
                leaveToHomeAndFinish("runtime_lease_expired")
                return
            }
            runtimeRenewedAt = nowElapsed
        }
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
        val ruleGroupId = rule.getString(RuleContract.KEY_GROUP_ID).orEmpty()
        if (!adOnly && !rule.getBoolean(RuleContract.KEY_ENABLED, false)) {
            finishWithoutAnimation("rule_disabled")
            return
        }
        if (
            !nonRoot && !adOnly &&
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
        if (adOnly) {
            leaveToHomeAndFinish("legacy_ad_request_rejected")
            return
        }
        val grouped = !rule.getString(RuleContract.KEY_GROUP_ID).isNullOrBlank()
        val constraints = buildList {
            if (!grouped && rule.getBoolean(RuleContract.KEY_SCHEDULE_ENABLED, false)) {
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
            ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), java.time.ZoneId.systemDefault()),
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
            groupVersionUnchanged && rule.getBoolean(RuleContract.KEY_GROUP_PER_LAUNCH_ENABLED, false)
        val appPerReached = QuotaKind.APP_PER_LAUNCH in reachedKinds &&
            !grouped && ruleVersionUnchanged && rule.getBoolean(RuleContract.KEY_PER_LAUNCH_ENABLED, false)
        val perSessionStillBlocked = (nonRoot && sessionResetAtMillis > nowMillis) ||
            cooldownActive || effectiveCooldownEnd <= 0L
        val snapshot = RuleDecisionSnapshot(
            scheduleBlocked = scheduleBlocked,
            cooldownRemainingMillis = (effectiveCooldownEnd - nowMillis).coerceAtLeast(0L),
            appDailyRemainingMillis = 0L.takeIf { appDailyReached },
            groupDailyRemainingMillis = 0L.takeIf { groupDailyReached },
            appPerLaunchRemainingMillis = 0L.takeIf { appPerReached && perSessionStillBlocked },
            groupPerLaunchRemainingMillis = 0L.takeIf { groupPerReached && perSessionStillBlocked },
            grouped = grouped,
            appDailyMeasurement = if (!grouped && rule.getBoolean(RuleContract.KEY_DAILY_ENABLED, false)) {
                RuleUsageMeasurement.fromProvider(
                    rule.getLong(RuleContract.KEY_SYSTEM_TODAY_USED_MS, -1L),
                    rule.getLong(RuleContract.KEY_DAILY_LIMIT_SECONDS, -1L),
                    RuleRepository.MIN_LIMIT_SECONDS, RuleRepository.MAX_LIMIT_SECONDS,
                )
            } else null,
            groupDailyMeasurement = if (grouped && rule.getBoolean(RuleContract.KEY_GROUP_DAILY_ENABLED, false)) {
                RuleUsageMeasurement.fromProvider(
                    rule.getLong(RuleContract.KEY_GROUP_TODAY_USED_MS, -1L),
                    rule.getLong(RuleContract.KEY_GROUP_DAILY_LIMIT_SECONDS, -1L),
                    RuleRepository.MIN_LIMIT_SECONDS, RuleRepository.MAX_LIMIT_SECONDS,
                )
            } else null,
            availabilityKnown = if (grouped) {
                !rule.getBoolean(RuleContract.KEY_GROUP_DAILY_ENABLED, false) || groupDailyReached ||
                    rule.getLong(RuleContract.KEY_GROUP_TODAY_USED_MS, -1L) >= 0L
            } else {
                !rule.getBoolean(RuleContract.KEY_DAILY_ENABLED, false) || appDailyReached ||
                    rule.getLong(RuleContract.KEY_SYSTEM_TODAY_USED_MS, -1L) >= 0L
            },
        )
        restrictionSnapshot = snapshot
        restrictionNextAvailable = RuleAvailabilityPolicy.nextAvailable(
            snapshot, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), java.time.ZoneId.systemDefault()),
            constraints,
            // Only the non-root adapter knows a fixed session reset. Hook reset stays conditional.
            sessionResetAt = sessionResetAtMillis.takeIf { nonRoot && it > nowMillis }?.let {
                ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault())
            },
        )
        renderRestrictionDetails()
        when {
            snapshot.primaryReason == RestrictionReason.SCHEDULE -> updateRestrictionState(
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
            snapshot.primaryReason == RestrictionReason.COOLDOWN -> {
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
            snapshot.primaryReason == RestrictionReason.GROUP_DAILY -> updateRestrictionState(
                "GROUP_DAILY:${LocalDate.now()}",
                if (english) "Group allowance exhausted" else "今日分组额度已耗尽",
                if (english) "Available again after the daily reset." else "每日额度重置后可再次使用",
            )
            snapshot.primaryReason == RestrictionReason.APP_DAILY -> updateRestrictionState(
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

    private fun renderRestrictionDetails() {
        if (!::restrictionDetailsView.isInitialized) return
        val snapshot = restrictionSnapshot
        restrictionDetailsView.visibility = if (snapshot?.primaryReason != null) View.VISIBLE else View.GONE
        val toggle = if (english) {
            if (restrictionDetailsExpanded) "Hide restriction details" else "Show restriction details"
        } else if (restrictionDetailsExpanded) "收起限制详情" else "展开限制详情"
        restrictionDetailsView.text = if (restrictionDetailsExpanded && snapshot != null) {
            "$toggle\n${RestrictionReasonText.details(snapshot, english)}"
        } else toggle
    }

    private fun startParentUnlock() {
        if (parentAuthInFlight) return
        diagnostic(
            "INFO",
            targetPackage,
            "PARENT_AUTH_BUTTON_TAPPED",
            "source=break_page state=${lastRestrictionState.take(80)}",
        )
        val settings = RuleRepository(this).getGlobalSettings()
        val privatePinReady = ChildLockRepository(this).isEnabled()
        if (!settings.childLockEnabled || !privatePinReady || controlSessionId.isBlank()) {
            val failure = when {
                !settings.childLockEnabled -> "child_lock_disabled"
                !privatePinReady -> "child_lock_pin_missing"
                else -> "legacy_hook_session_missing"
            }
            showParentAuthFeedback(
                when {
                    !settings.childLockEnabled && english -> "Control Lock is disabled in Time Stop settings"
                    !settings.childLockEnabled -> "管控锁尚未开启，请先在时停设置中开启"
                    !privatePinReady && english -> "Control Lock PIN data is unavailable. Set the PIN again in Time Stop."
                    !privatePinReady -> "管控锁 PIN 数据不可用，请打开时停重新设置 PIN"
                    english -> "The target app is using an older Hook. Force stop and reopen it."
                    else -> "目标应用仍在运行旧版 Hook，请强停并重新打开后再试"
                },
            )
            diagnostic(
                "WARN",
                targetPackage,
                "PARENT_AUTH_UI_REJECTED",
                "source=break_page reason=$failure childLock=${settings.childLockEnabled} " +
                    "privatePinReady=$privatePinReady sessionBlank=${controlSessionId.isBlank()}",
            )
            return
        }
        setParentAuthBusy(true)
        val result = runCatching {
            contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_CREATE_PARENT_AUTH_CHALLENGE,
                targetPackage,
                Bundle().apply {
                    putString(RuleContract.KEY_PROCESS_SESSION_ID, controlSessionId)
                    putString(
                        RuleContract.KEY_INCIDENT_ID,
                        "break-page:$targetPackage:$lastRestrictionState",
                    )
                    putString(
                        RuleContract.KEY_PARENT_AUTH_REASON,
                        lastRestrictionState.substringBefore(':').ifBlank { "RESTRICTION" },
                    )
                },
            )
        }.onFailure { error ->
            diagnostic(
                "ERROR",
                targetPackage,
                "PARENT_AUTH_CHALLENGE_FAILED",
                "source=break_page exception=${error.javaClass.simpleName}:${error.message}",
            )
        }.getOrNull()
        val token = result?.takeIf { it.getBoolean(RuleContract.KEY_OK, false) }
            ?.getString(RuleContract.KEY_PARENT_AUTH_TOKEN).orEmpty()
        if (token.isBlank()) {
            val failure = result?.getString(RuleContract.KEY_MESSAGE).orEmpty().ifBlank { "unknown" }
            diagnostic(
                "ERROR",
                targetPackage,
                "PARENT_AUTH_CHALLENGE_FAILED",
                "source=break_page reason=${failure.take(120)}",
            )
            setParentAuthBusy(false)
            showParentAuthFeedback(parentAuthFailureMessage(failure))
            return
        }
        parentAuthToken = token
        val intent = Intent(this, ParentUnlockActivity::class.java).apply {
            putExtra(ParentUnlockActivity.EXTRA_TOKEN, token)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_PARENT_UNLOCK)
        }.onSuccess {
            diagnostic(
                "INFO",
                targetPackage,
                "PARENT_AUTH_ACTIVITY_LAUNCH_REQUESTED",
                "source=break_page",
            )
            scheduleParentAuthPoll(token)
        }.onFailure { error ->
            diagnostic(
                "ERROR",
                targetPackage,
                "PARENT_AUTH_ACTIVITY_FAILED",
                "source=break_page exception=${error.javaClass.simpleName}:${error.message}",
            )
            setParentAuthBusy(false)
            showParentAuthFeedback(
                if (english) "Unable to open PIN verification" else "无法打开 PIN 验证页面",
            )
        }
    }

    @Deprecated("Legacy result callback is sufficient for this private activity flow.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PARENT_UNLOCK) return
        if (resultCode == RESULT_OK) {
            completeParentOverride()
        } else {
            pollParentAuthStatus(parentAuthToken, allowRetry = true)
        }
    }

    private fun scheduleParentAuthPoll(token: String) {
        parentAuthPoll?.let(handler::removeCallbacks)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                if (!parentAuthInFlight || token != parentAuthToken) return
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                val retry = elapsed < ParentUnlockActivity.MAX_WAIT_MILLIS + 1_000L
                if (!pollParentAuthStatus(token, retry) && retry) {
                    handler.postDelayed(this, PARENT_AUTH_POLL_MILLIS)
                }
            }
        }
        parentAuthPoll = poll
        handler.post(poll)
    }

    /** Returns true when the attempt reached a terminal state. */
    private fun pollParentAuthStatus(token: String, allowRetry: Boolean): Boolean {
        if (token.isBlank()) {
            if (!allowRetry) setParentAuthBusy(false)
            return !allowRetry
        }
        val status = runCatching {
            contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_GET_PARENT_AUTH_STATUS,
                targetPackage,
                Bundle().apply {
                    putString(RuleContract.KEY_PARENT_AUTH_TOKEN, token)
                    putString(RuleContract.KEY_PROCESS_SESSION_ID, controlSessionId)
                },
            )
        }.getOrNull()?.getString(RuleContract.KEY_PARENT_AUTH_STATUS).orEmpty()
        return when (status) {
            "GRANTED" -> {
                completeParentOverride()
                true
            }
            "VERIFIED_WAITING_AD", "AD_REWARDED" -> { setParentAuthBusy(false); true }
            "DENIED", "TIMED_OUT", "INVALID" -> {
                diagnostic(
                    "WARN",
                    targetPackage,
                    if (status == "TIMED_OUT") "PARENT_AUTH_TIMEOUT" else "PARENT_AUTH_FAILED",
                    "source=break_page status=$status",
                )
                setParentAuthBusy(false)
                true
            }
            else -> {
                if (!allowRetry) {
                    diagnostic(
                        "WARN",
                        targetPackage,
                        "PARENT_AUTH_TIMEOUT",
                        "source=break_page status_poll_timeout",
                    )
                    setParentAuthBusy(false)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun completeParentOverride() {
        if (!parentAuthInFlight) return
        diagnostic(
            "INFO",
            targetPackage,
            "TEMPORARY_OVERRIDE_ACTIVATED",
            "source=break_page session=${controlSessionId.take(40)}",
        )
        setParentAuthBusy(false, preservePending = true)
        finishWithoutAnimation("parent_override_granted")
    }

    private fun setParentAuthBusy(busy: Boolean, preservePending: Boolean = false) {
        if (!busy && !preservePending) restoreRuntimeRestriction()
        parentAuthInFlight = busy
        if (!busy) {
            parentAuthToken = ""
            parentAuthPoll?.let(handler::removeCallbacks)
            parentAuthPoll = null
        }
        parentUnlockView.isEnabled = !busy
        parentUnlockView.alpha = if (busy) 0.6f else 1f
        parentUnlockView.text = when {
            busy && english -> "Opening verification…"
            busy -> "正在打开验证…"
            english -> "Temporary PIN access"
            else -> "PIN 临时放行"
        }
        if (::actionFeedbackView.isInitialized) {
            actionFeedbackView.visibility = if (busy) View.VISIBLE else actionFeedbackView.visibility
            if (busy) {
                actionFeedbackView.text = if (english) {
                    "PIN verification is in progress. Keep this page open."
                } else {
                    "正在验证 PIN，请保持当前页面。"
                }
            }
        }
    }

    private fun showParentAuthFeedback(message: String) {
        if (::actionFeedbackView.isInitialized) {
            actionFeedbackView.visibility = View.VISIBLE
            actionFeedbackView.text = message
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun parentAuthFailureMessage(reason: String): String = when (reason) {
        "child_lock_disabled" -> if (english) {
            "Control Lock is disabled in Time Stop settings"
        } else {
            "管控锁尚未开启，请先在时停设置中开启"
        }
        "child_lock_pin_missing" -> if (english) {
            "Control Lock PIN data is unavailable. Set the PIN again in Time Stop."
        } else {
            "管控锁 PIN 数据不可用，请打开时停重新设置 PIN"
        }
        "invalid_challenge_fields" -> if (english) {
            "The control session is outdated. Force stop and reopen the target app."
        } else {
            "当前管控会话已过期，请强停并重新打开目标应用"
        }
        "hook_not_controller" -> if (english) {
            "The protection mode changed. Force stop and reopen the target app."
        } else {
            "保护模式已经切换，请强停并重新打开目标应用"
        }
        else -> if (english) {
            "Could not open parent verification. Check Control Lock settings."
        } else {
            "无法打开PIN 验证，请检查管控锁设置"
        }
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
        val snapshot = restrictionSnapshot
        val primary = snapshot?.primaryReason
        val availability = restrictionNextAvailable?.format(
            if (english) DateTimeFormatter.ofPattern("MMM d, E HH:mm:ss", Locale.ENGLISH)
            else DateTimeFormatter.ofPattern("M月d日 E HH:mm:ss", Locale.CHINA),
        )
        val authoritativeMessage = when {
            primary == null -> message
            availability != null -> if (english) {
                "Expected availability: $availability. All limits will be checked again."
            } else "预计可用：$availability，届时仍会重新检查全部限制。"
            primary == RestrictionReason.SCHEDULE || primary == RestrictionReason.COOLDOWN ||
                primary == RestrictionReason.APP_DAILY || primary == RestrictionReason.GROUP_DAILY ->
                if (english) "Availability is not yet known. All restrictions must clear before continuing."
                else "暂时无法确定可用时间；全部限制解除后才可使用，请展开查看详情。"
            else -> message
        }
        updateText(primary?.let { RestrictionReasonText.label(it, english) } ?: title, authoritativeMessage)
        val settings = RuleRepository(this).getGlobalSettings()
        extensionView.visibility = if (settings.extensionEnabled &&
            (state.startsWith("APP_DAILY") || state.startsWith("APP_SESSION") || state.startsWith("GROUP_") ||
                state.contains("PLAN") || state.contains("PER_SESSION") || state.contains("SESSION_GRACE")) &&
            !state.contains("COOLDOWN") && !state.contains("SCHEDULE")) View.VISIBLE else View.GONE
        val quota = runCatching { RuleRepository(this).previewExtension(targetPackage, controlSessionId) }.getOrNull()
        val needsCooldown = RuleRepository(this).extensionRequiresCooldown(targetPackage)
        extensionView.isEnabled = quota?.allowed == true && !needsCooldown
        extensionView.text = when {
            needsCooldown -> if (english) "This rule requires a cooling-off period; extensions are unavailable" else "当前规则要求到限后冷却，不可延时"
            quota == null -> if (english) "Extension status unavailable; reopen this page to retry" else "暂时无法读取延时次数，请重新进入管控页"
            quota.remainingCount == 0 -> if (english) "Today's extensions used up" else "今日延时次数已用完"
            quota.remainingSessionCount == 0 -> if (english) "This session's extensions used up" else "本轮延时次数已用完"
            else -> if (english) "Extend ${settings.extensionSeconds / 60} min · ${quota.remainingCount} left today / ${quota.remainingSessionCount} this session"
                else "延时 ${settings.extensionSeconds / 60} 分钟 · 今日剩 ${quota.remainingCount} 次 / 本轮剩 ${quota.remainingSessionCount} 次"
        }
        updateTemporaryAccessSection()
    }

    private fun requestFreeExtension() {
        extensionView.isEnabled = false
        val response = runCatching {
            contentResolver.call(RuleContract.CONTENT_URI, "request_free_extension", targetPackage, Bundle().apply {
                putString(RuleContract.KEY_PROCESS_SESSION_ID, controlSessionId)
                putString(RuleContract.KEY_INCIDENT_ID, restrictionIncidentId)
                putString("extension_request_id", extensionRequestId)
            })
        }.getOrNull()
        if (response?.getBoolean(RuleContract.KEY_OK, false) == true) {
            finishWithoutAnimation("free_extension_granted")
        } else {
            extensionView.isEnabled = true
            actionFeedbackView.visibility = View.VISIBLE
            actionFeedbackView.text = when (response?.getString(RuleContract.KEY_MESSAGE)) {
                "extension_limit_reached" -> if (english) "Daily or session extension limit reached" else "每日或本轮延时次数已用完"
                "cooldown_required" -> if (english) "Please wait for the cooling-off period" else "当前需完成冷却后才能继续使用"
                "schedule_blocked" -> if (english) "Extensions are unavailable during a blocked period" else "禁止时段不可延时"
                "stale_session" -> if (english) "This control session has changed. Reopen the target app." else "本次管控状态已变化，请重新打开目标应用"
                "rule_not_configured" -> if (english) "The controlling rule has changed." else "管控规则已变化，请重新打开目标应用"
                else -> if (english) "Extension not granted. No time has been added; try again later." else "延时未成功，未增加使用时间，请稍后重试"
            }
        }
    }

    private fun updateTemporaryAccessSection() {
        if (!::temporaryAccessTitleView.isInitialized) return
        val visible = RestrictionPagePresentationPolicy.showTemporaryAccess(
            allowPin = ::parentUnlockView.isInitialized && parentUnlockView.visibility == View.VISIBLE,
            allowExtension = ::extensionView.isInitialized && extensionView.visibility == View.VISIBLE,
        )
        temporaryAccessTitleView.visibility = if (visible) View.VISIBLE else View.GONE
        temporaryAccessTitleView.text = if (english) "Temporary access" else "临时放行"
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
        releaseRestrictionUi()
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
        // Do not leave the restriction task behind. A lingering page can be restored by the
        // launcher or recents and makes one user exit look like two separate exits.
        finishWithoutAnimation("user_exit")
    }

    private fun claimRestrictionUi(
        packageName: String = targetPackage,
        incidentId: String = restrictionIncidentId,
    ): Boolean {
        if (packageName.isBlank() || incidentId.isBlank()) return false
        val rule = runCatching { contentResolver.call(RuleContract.CONTENT_URI,
            RuleContract.METHOD_GET_RULE, packageName, null) }.getOrNull()
            ?.takeIf { it.getBoolean(RuleContract.KEY_OK, false) } ?: return false
        if (runtimeIdentity == null) runtimeIdentity = Bundle().apply {
            putString(RuleContract.KEY_PROCESS_SESSION_ID, controlSessionId)
            putLong(RuleContract.KEY_VERSION, initialRuleVersion)
            putLong(RuleContract.KEY_GROUP_VERSION, initialGroupVersion)
            putLong(RuleContract.KEY_PROTECTION_MODE_GENERATION, rule.getLong(RuleContract.KEY_PROTECTION_MODE_GENERATION, Long.MIN_VALUE))
        }
        val response = runCatching {
            contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_CLAIM_RESTRICTION_UI,
                packageName,
                Bundle(runtimeIdentity!!).apply { putString(RuleContract.KEY_INCIDENT_ID, incidentId) },
            )
        }.getOrNull() ?: return false
        if (!response.getBoolean(RuleContract.KEY_OK, false)) return false
        restrictionIncidentId = response.getString(RuleContract.KEY_INCIDENT_ID).orEmpty()
        return restrictionIncidentId.isNotBlank()
    }

    private fun transitionRuntime(next: String): Boolean {
        val identity = runtimeIdentity ?: return false
        return runCatching { contentResolver.call(RuleContract.CONTENT_URI,
            RuleContract.METHOD_TRANSITION_CONTROL_RUNTIME, targetPackage, Bundle(identity).apply {
                putString(RuleContract.KEY_INCIDENT_ID, restrictionIncidentId)
                putString(RuleContract.KEY_CONTROL_RUNTIME_STATE, next)
            })?.getBoolean(RuleContract.KEY_OK, false) == true }.getOrDefault(false)
    }

    private fun restoreRuntimeRestriction() {
        if (!transitionRuntime("EXECUTING_RESTRICTION") || !transitionRuntime("RESTRICTION_VISIBLE")) {
            leaveToHomeAndFinish("runtime_restore_failed")
        }
    }

    private fun releaseRestrictionUi() {
        if (targetPackage.isBlank() || restrictionIncidentId.isBlank()) return
        runCatching {
            contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_RELEASE_RESTRICTION_UI,
                targetPackage,
                Bundle(runtimeIdentity ?: Bundle()).apply { putString(RuleContract.KEY_INCIDENT_ID, restrictionIncidentId) },
            )
        }
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
        private val ACTIVE_PAGE_LOCK = Any()
        private var activePage: WeakReference<LimitBlockActivity>? = null

        /** Closes a stale standalone page when parent authentication grants the override. */
        fun finishAuthorizedPageForTarget(target: String, reason: String) {
            if (target.isBlank()) return
            val page = synchronized(ACTIVE_PAGE_LOCK) { activePage?.get() }
            if (page == null || page.isFinishing || page.isDestroyed || page.targetPackage != target) return
            page.diagnostic(
                "INFO",
                target,
                "BREAK_PAGE_CLOSED_AFTER_PARENT_AUTH",
                "reason=${reason.take(80)}",
            )
            page.finishWithoutAnimation("parent_override_granted")
        }

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
        const val EXTRA_AD_ONLY = "ad_only"
        const val EXTRA_INCIDENT_ID = "incident_id"
        const val EXTRA_CONTROL_SESSION_ID = "control_session_id"
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
        private const val STATE_AD_ONLY = "state_ad_only"
        private const val STATE_INCIDENT_ID = "state_incident_id"
        private const val STATE_CONTROL_SESSION_ID = "state_control_session_id"
        private const val REQUEST_PARENT_UNLOCK = 901
        private const val PARENT_AUTH_POLL_MILLIS = 250L
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
            incidentId = restrictionIncidentId.takeIf { it.isNotBlank() },
        )
    }
}
