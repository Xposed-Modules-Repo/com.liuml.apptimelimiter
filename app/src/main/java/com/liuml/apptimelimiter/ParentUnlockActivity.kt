package com.liuml.apptimelimiter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.core.ParentOverrideDurationPolicy
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.ipc.RuleContract
import com.liuml.apptimelimiter.localization.AppLocaleController
import com.liuml.apptimelimiter.security.ChildLockRepository
import com.liuml.apptimelimiter.security.PinVerificationResult
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import com.liuml.apptimelimiter.ui.theme.TimeStopTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Secure, token-gated PIN UI. Third-party target processes never receive the PIN. */
class ParentUnlockActivity : FragmentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var token: String
    private var targetPackage = ""
    private var deferGrantForAd = false
    private var reason = ""
    private var authorized = false
    private var expiresAtMillis = 0L
    private var completed = false
    private val childLockRepository by lazy(LazyThreadSafetyMode.NONE) {
        ChildLockRepository(this)
    }
    private var errorText by mutableStateOf("")
    private var verificationInProgress by mutableStateOf(false)
    private var lockoutRemainingSeconds by mutableLongStateOf(0L)
    private var remainingSeconds by mutableLongStateOf(MAX_WAIT_MILLIS / 1_000L)
    private var selectedDurationMinutes by mutableIntStateOf(
        ParentOverrideDurationPolicy.DEFAULT_MINUTES,
    )
    private val timeout = Runnable {
        if (completed) return@Runnable
        Log.w(
            TAG,
            "Parent authentication timed out for ${targetPackage.take(160)}",
        )
        Toast.makeText(
            this,
            if (resources.configuration.locales[0]?.language == "en") {
                "Parent verification timed out. The restriction will continue."
            } else {
                "家长验证已超时，将继续执行限制"
            },
            Toast.LENGTH_LONG,
        ).show()
        complete(granted = false, event = "PARENT_AUTH_TIMEOUT")
    }
    private val countdown = object : Runnable {
        override fun run() {
            if (completed) return
            remainingSeconds = (remainingSeconds - 1L).coerceAtLeast(0L)
            if (remainingSeconds > 0L) handler.postDelayed(this, 1_000L)
        }
    }
    private val lockoutCountdown = object : Runnable {
        override fun run() {
            if (completed || lockoutRemainingSeconds <= 0L) return
            lockoutRemainingSeconds = (lockoutRemainingSeconds - 1L).coerceAtLeast(0L)
            if (lockoutRemainingSeconds > 0L) handler.postDelayed(this, 1_000L)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val languageMode = runCatching {
            RuleRepository(newBase).getGlobalSettings().languageMode
        }.getOrDefault(com.liuml.apptimelimiter.data.AppLanguageMode.SYSTEM)
        super.attachBaseContext(AppLocaleController.wrap(newBase, languageMode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val restored = savedInstanceState?.getBoolean(STATE_AUTHORIZED, false) == true
        if (restored) {
            token = savedInstanceState?.getString(STATE_TOKEN).orEmpty()
            targetPackage = savedInstanceState?.getString(STATE_TARGET_PACKAGE).orEmpty()
            reason = savedInstanceState?.getString(STATE_REASON).orEmpty()
            expiresAtMillis = savedInstanceState?.getLong(STATE_EXPIRES_AT, 0L) ?: 0L
            selectedDurationMinutes = ParentOverrideDurationPolicy.normalizeMinutes(
                savedInstanceState?.getInt(
                    STATE_DURATION_MINUTES,
                    ParentOverrideDurationPolicy.DEFAULT_MINUTES,
                ) ?: ParentOverrideDurationPolicy.DEFAULT_MINUTES,
            )
            deferGrantForAd = savedInstanceState?.getBoolean(STATE_DEFER_GRANT_FOR_AD, false) == true
            authorized = token.isNotBlank() && targetPackage.isNotBlank() &&
                expiresAtMillis > System.currentTimeMillis()
        } else {
            token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
            deferGrantForAd = intent.getBooleanExtra(EXTRA_DEFER_GRANT_FOR_AD, false)
            val consumed = consumeChallenge(token)
            if (consumed != null) {
                targetPackage = consumed.getString(EXTRA_TARGET_PACKAGE).orEmpty()
                reason = consumed.getString(RuleContract.KEY_PARENT_AUTH_REASON).orEmpty()
                expiresAtMillis = consumed.getLong(
                    RuleContract.KEY_BREAK_SESSION_EXPIRES_AT_MS,
                    0L,
                )
                authorized = targetPackage.isNotBlank() &&
                    expiresAtMillis > System.currentTimeMillis()
            }
        }
        if (!authorized) {
            diagnostic("PARENT_AUTH_ACTIVITY_REJECTED", "reason=invalid_or_expired_challenge")
            Toast.makeText(
                this,
                if (resources.configuration.locales[0]?.language == "en") {
                    "Parent verification expired. Try again."
                } else {
                    "家长验证已失效，请重新点击解锁"
                },
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        diagnostic("PARENT_AUTH_ACTIVITY_SHOWN", "reason=${reason.take(60)}")
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    diagnostic("PARENT_AUTH_BACK_PRESSED", "reason=${reason.take(60)}")
                    complete(false, "PARENT_AUTH_CANCELLED")
                }
            },
        )
        val settings = RuleRepository(this).getGlobalSettings()
        val english = resources.configuration.locales[0]?.language == "en"
        val initialLockoutMillis = (
            childLockRepository.snapshot().lockoutUntilMillis - System.currentTimeMillis()
            ).coerceAtLeast(0L)
        lockoutRemainingSeconds = (initialLockoutMillis + 999L) / 1_000L
        if (lockoutRemainingSeconds > 0L) {
            handler.postDelayed(lockoutCountdown, 1_000L)
        }
        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(targetPackage, 0),
            ).toString()
        }.getOrDefault(targetPackage)
        setContent {
            TimeStopTheme(settings.themeMode, settings.themeColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val keyboard = LocalSoftwareKeyboardController.current
                    var pin by androidx.compose.runtime.remember { mutableStateOf("") }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (english) "Parent temporary unlock" else "家长临时解锁",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (english) {
                                "Enter the Time Stop PIN to temporarily allow $appLabel for this foreground session."
                            } else {
                                "输入时停 PIN，临时放行“$appLabel”的本次前台会话。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (english) "$remainingSeconds seconds remaining" else "剩余 $remainingSeconds 秒",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(18.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { value ->
                                pin = value.filter(Char::isDigit).take(8)
                                errorText = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (english) "4-8 digit PIN" else "4–8 位数字 PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            keyboardActions = KeyboardActions(onDone = {
                                keyboard?.hide()
                                verify(pin, english, selectedDurationMinutes)
                            }),
                            isError = errorText.isNotBlank() || lockoutRemainingSeconds > 0L,
                            enabled = !verificationInProgress && lockoutRemainingSeconds == 0L,
                            supportingText = if (lockoutRemainingSeconds > 0L) {
                                {
                                    Text(
                                        if (english) {
                                            "Try again in $lockoutRemainingSeconds seconds"
                                        } else {
                                            "$lockoutRemainingSeconds 秒后再试"
                                        },
                                    )
                                }
                            } else if (errorText.isNotBlank()) {
                                { Text(errorText) }
                            } else null,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (english) {
                                "Temporarily allow for $selectedDurationMinutes min"
                            } else {
                                "临时放行 $selectedDurationMinutes 分钟"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Slider(
                            value = selectedDurationMinutes.toFloat(),
                            onValueChange = {
                                selectedDurationMinutes = ParentOverrideDurationPolicy
                                    .normalizeMinutes(it.roundToInt())
                            },
                            valueRange = ParentOverrideDurationPolicy.MIN_MINUTES.toFloat()..
                                ParentOverrideDurationPolicy.MAX_MINUTES.toFloat(),
                            steps = ParentOverrideDurationPolicy.MAX_MINUTES -
                                ParentOverrideDurationPolicy.MIN_MINUTES - 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("${ParentOverrideDurationPolicy.MIN_MINUTES}")
                            Text("${ParentOverrideDurationPolicy.MAX_MINUTES}")
                        }
                        Spacer(Modifier.height(12.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Button(
                                onClick = { verify(pin, english, selectedDurationMinutes) },
                                enabled = !verificationInProgress &&
                                    lockoutRemainingSeconds == 0L && pin.length in 4..8,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (english) {
                                        if (verificationInProgress) {
                                            "Verifying…"
                                        } else {
                                            "Verify and allow $selectedDurationMinutes min"
                                        }
                                    } else {
                                        if (verificationInProgress) {
                                            "正在验证…"
                                        } else {
                                            "验证并放行 $selectedDurationMinutes 分钟"
                                        }
                                    },
                                )
                            }
                            TextButton(
                                onClick = {
                                    diagnostic(
                                        "PARENT_AUTH_CANCEL_BUTTON_TAPPED",
                                        "reason=${reason.take(60)}",
                                    )
                                    complete(false, "PARENT_AUTH_CANCELLED")
                                },
                            ) {
                                Text(if (english) "Cancel" else "取消")
                            }
                        }
                    }
                }
            }
        }
        val remainingMillis = (expiresAtMillis - System.currentTimeMillis())
            .coerceIn(1L, MAX_WAIT_MILLIS)
        remainingSeconds = (remainingMillis + 999L) / 1_000L
        handler.postDelayed(timeout, remainingMillis)
        handler.postDelayed(countdown, 1_000L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!authorized || completed) return
        outState.putBoolean(STATE_AUTHORIZED, true)
        outState.putString(STATE_TOKEN, token)
        outState.putString(STATE_TARGET_PACKAGE, targetPackage)
        outState.putString(STATE_REASON, reason)
        outState.putLong(STATE_EXPIRES_AT, expiresAtMillis)
        outState.putInt(STATE_DURATION_MINUTES, selectedDurationMinutes)
        outState.putBoolean(STATE_DEFER_GRANT_FOR_AD, deferGrantForAd)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!completed) {
            diagnostic(
                "PARENT_AUTH_ACTIVITY_DESTROYED_UNEXPECTEDLY",
                "finishing=$isFinishing changingConfigurations=$isChangingConfigurations",
            )
            Log.w(
                TAG,
                "Parent authentication activity destroyed without an explicit result; " +
                    "finishing=$isFinishing changingConfigurations=$isChangingConfigurations",
            )
        }
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!completed) {
            diagnostic("PARENT_AUTH_USER_LEAVE_HINT", "reason=${reason.take(60)}")
        }
    }

    private fun verify(pin: String, english: Boolean, durationMinutes: Int) {
        if (verificationInProgress || completed) return
        if (lockoutRemainingSeconds > 0L) {
            errorText = if (english) {
                "Try again in $lockoutRemainingSeconds seconds"
            } else {
                "$lockoutRemainingSeconds 秒后再试"
            }
            return
        }
        if (!childLockRepository.isValidPinFormat(pin)) {
            errorText = if (english) "Enter 4-8 digits" else "请输入 4–8 位数字"
            return
        }
        verificationInProgress = true
        val submittedPin = pin
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { childLockRepository.verify(submittedPin) }
                    .getOrElse { error ->
                        PinVerificationResult.Error(error.javaClass.simpleName.take(80))
                    }
            }
            if (completed || isFinishing || isDestroyed) return@launch
            verificationInProgress = false
            when (result) {
                PinVerificationResult.Success -> completeAfterSuccessfulPin(durationMinutes)
                PinVerificationResult.NotConfigured -> {
                    errorText = if (english) {
                        "Control Lock is unavailable"
                    } else {
                        "管控锁配置不可用"
                    }
                }
                is PinVerificationResult.Locked -> {
                    val seconds = (result.remainingMillis + 999L) / 1_000L
                    lockoutRemainingSeconds = seconds
                    handler.removeCallbacks(lockoutCountdown)
                    if (seconds > 0L) handler.postDelayed(lockoutCountdown, 1_000L)
                    errorText = if (english) {
                        "Try again in $seconds seconds"
                    } else {
                        "$seconds 秒后再试"
                    }
                    diagnostic("PARENT_AUTH_LOCKED_OUT", "remainingMs=${result.remainingMillis}")
                    Log.w(TAG, "Parent PIN locked; remainingMs=${result.remainingMillis}")
                }
                is PinVerificationResult.Rejected -> {
                    errorText = if (english) "Incorrect PIN" else "PIN 不正确"
                    diagnostic("PARENT_AUTH_FAILED", "reason=incorrect_pin")
                    Log.w(TAG, "Parent PIN rejected")
                }
                is PinVerificationResult.Error -> {
                    errorText = if (english) {
                        "PIN verification failed. Try again."
                    } else {
                        "PIN 验证异常，请重试"
                    }
                    Log.e(TAG, "PIN verification failed: ${result.cause}")
                    diagnostic("PARENT_AUTH_VERIFY_ERROR", "cause=${result.cause.take(80)}")
                }
            }
        }
    }

    private fun completeAfterSuccessfulPin(durationMinutes: Int) {
        if (!deferGrantForAd) {
            complete(granted = true, event = "PARENT_AUTH_SUCCEEDED", durationMinutes = durationMinutes)
            return
        }
        val adRequired = RuleRepository(this).claimParentUnlockAdRequired(
            java.time.LocalDate.now().toString(),
        )
        if (!adRequired) {
            complete(granted = true, event = "PARENT_AUTH_SUCCEEDED", durationMinutes = durationMinutes)
            return
        }
        val marked = runCatching {
            contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_MARK_PARENT_AUTH_VERIFIED_FOR_AD,
                null,
                Bundle().apply { putString(RuleContract.KEY_PARENT_AUTH_TOKEN, token) },
            )
        }.getOrNull()?.getBoolean(RuleContract.KEY_OK, false) == true
        if (!marked) {
            // A successful parent verification must never be lost because the ad handoff expired.
            complete(granted = true, event = "PARENT_AUTH_SUCCEEDED_AD_HANDOFF_FAILED", durationMinutes = durationMinutes)
            return
        }
        completed = true
        handler.removeCallbacksAndMessages(null)
        diagnostic("PARENT_AUTH_VERIFIED_WAITING_AD", "reason=${reason.take(60)}")
        setResult(
            RESULT_PIN_VERIFIED_FOR_AD,
            Intent().putExtra(EXTRA_DURATION_MINUTES, ParentOverrideDurationPolicy.normalizeMinutes(durationMinutes)),
        )
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun consumeChallenge(value: String): Bundle? = runCatching {
        contentResolver.call(
            RuleContract.CONTENT_URI,
            RuleContract.METHOD_CONSUME_PARENT_AUTH_CHALLENGE,
            null,
            Bundle().apply { putString(RuleContract.KEY_PARENT_AUTH_TOKEN, value) },
        )
    }.getOrNull()?.takeIf { it.getBoolean(RuleContract.KEY_OK, false) }

    private fun complete(
        granted: Boolean,
        event: String,
        durationMinutes: Int = ParentOverrideDurationPolicy.DEFAULT_MINUTES,
    ) {
        if (completed) return
        completed = true
        handler.removeCallbacksAndMessages(null)
        val persisted = runCatching {
            contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_COMPLETE_PARENT_AUTH_CHALLENGE,
                null,
                Bundle().apply {
                    putString(RuleContract.KEY_PARENT_AUTH_TOKEN, token)
                    putBoolean(RuleContract.KEY_PARENT_AUTH_GRANTED, granted)
                    if (granted) {
                        putInt(
                            RuleContract.KEY_PARENT_OVERRIDE_DURATION_MINUTES,
                            ParentOverrideDurationPolicy.normalizeMinutes(durationMinutes),
                        )
                    }
                },
            )
        }.getOrNull()?.getBoolean(RuleContract.KEY_OK, false) == true
        val effectiveGrant = granted && persisted
        if (effectiveGrant) {
            UsageStatsRepository(this).recordParentUnlockEvent(
                packageName = targetPackage,
                day = java.time.LocalDate.now(),
                eventId = token.hashCode().toString(),
            )
            // A successful PIN handoff owns the whole restriction UI flow. Close any stale
            // standalone page before returning to the target app; failed/cancelled auth keeps it.
            LimitBlockActivity.finishAuthorizedPageForTarget(
                target = targetPackage,
                reason = "parent_override_granted",
            )
        }
        diagnostic(
            when {
                granted && !persisted -> "PARENT_AUTH_COMPLETION_FAILED"
                effectiveGrant -> "PARENT_AUTH_UI_COMPLETED"
                else -> event
            },
            "reason=${reason.take(60)}, durationMinutes=${if (effectiveGrant) durationMinutes else 0}",
        )
        setResult(if (effectiveGrant) RESULT_OK else RESULT_CANCELED)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun diagnostic(event: String, message: String) {
        if (!RuleRepository(this).getGlobalSettings().diagnosticsEnabled) return
        DiagnosticsRepository(this).append("INFO", targetPackage.ifBlank { packageName }, event, message)
    }

    companion object {
        private const val TAG = "TimeStopParentAuth"
        const val EXTRA_TOKEN = "parent_auth_token"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_DEFER_GRANT_FOR_AD = "defer_parent_grant_for_ad"
        const val EXTRA_DURATION_MINUTES = "parent_override_duration_minutes"
        const val RESULT_PIN_VERIFIED_FOR_AD = RESULT_FIRST_USER + 41
        const val MAX_WAIT_MILLIS = 30_000L
        private const val STATE_AUTHORIZED = "state_authorized"
        private const val STATE_TOKEN = "state_token"
        private const val STATE_TARGET_PACKAGE = "state_target_package"
        private const val STATE_REASON = "state_reason"
        private const val STATE_EXPIRES_AT = "state_expires_at"
        private const val STATE_DURATION_MINUTES = "state_duration_minutes"
        private const val STATE_DEFER_GRANT_FOR_AD = "state_defer_grant_for_ad"
    }
}
