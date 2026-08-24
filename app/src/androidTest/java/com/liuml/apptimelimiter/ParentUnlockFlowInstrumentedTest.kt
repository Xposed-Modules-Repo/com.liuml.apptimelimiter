package com.liuml.apptimelimiter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.liuml.apptimelimiter.data.AppLanguageMode
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.LimitEnforcementMode
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.ipc.RuleContract
import com.liuml.apptimelimiter.security.ChildLockRepository
import com.liuml.apptimelimiter.security.ParentAuthStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ParentUnlockFlowInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = RuleRepository(context)
    private val childLockRepository = ChildLockRepository(context)
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        ParentAuthStore.clear()
        assertTrue(
            repository.save(
                AppRule(
                    packageName = TARGET_PACKAGE,
                    enabled = true,
                    dailyEnabled = true,
                    dailyLimitSeconds = 60L,
                ),
            ),
        )
        assertTrue(childLockRepository.enable(PIN))
        assertTrue(
            repository.saveGlobalSettings(
                repository.getGlobalSettings().copy(
                    childLockEnabled = true,
                    languageMode = AppLanguageMode.ENGLISH,
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        ParentAuthStore.clear()
        childLockRepository.disableAfterAuthentication()
        repository.saveGlobalSettings(
            repository.getGlobalSettings().copy(childLockEnabled = false),
        )
    }

    @Test
    fun pinActivityConsumesChallengeAndGrantsMatchingSession() {
        val challenge = context.contentResolver.call(
            RuleContract.CONTENT_URI,
            RuleContract.METHOD_CREATE_PARENT_AUTH_CHALLENGE,
            TARGET_PACKAGE,
            Bundle().apply {
                putString(RuleContract.KEY_PROCESS_SESSION_ID, SESSION_ID)
                putString(RuleContract.KEY_INCIDENT_ID, "instrumented-parent-auth")
                putString(RuleContract.KEY_PARENT_AUTH_REASON, "QUOTA")
            },
        )
        assertTrue(challenge?.getBoolean(RuleContract.KEY_OK, false) == true)
        val token = challenge?.getString(RuleContract.KEY_PARENT_AUTH_TOKEN).orEmpty()
        assertTrue(token.isNotBlank())

        context.startActivity(
            Intent(context, ParentUnlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ParentUnlockActivity.EXTRA_TOKEN, token)
            },
        )
        assertTrue(device.wait(Until.hasObject(By.text("Parent temporary unlock")), UI_TIMEOUT_MS))
        val pinField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), UI_TIMEOUT_MS)
        assertTrue(pinField != null)
        pinField.text = PIN
        val unlock = device.wait(Until.findObject(By.text("Unlock")), UI_TIMEOUT_MS)
        assertTrue(unlock != null)
        unlock.click()

        assertTrue(
            device.wait(
                Until.gone(By.text("Parent temporary unlock")),
                UI_TIMEOUT_MS,
            ),
        )
        val override = context.contentResolver.call(
            RuleContract.CONTENT_URI,
            RuleContract.METHOD_HAS_PARENT_OVERRIDE,
            TARGET_PACKAGE,
            Bundle().apply {
                putString(RuleContract.KEY_PROCESS_SESSION_ID, SESSION_ID)
            },
        )
        assertTrue(override?.getBoolean(RuleContract.KEY_OK, false) == true)
        assertTrue(override?.getBoolean(RuleContract.KEY_PARENT_AUTH_GRANTED, false) == true)
    }

    @Test
    fun restrictionPagePinButtonOpensVerificationAndGrantsOverride() {
        assertTrue(
            repository.saveGlobalSettings(
                repository.getGlobalSettings().copy(
                    childLockEnabled = true,
                    languageMode = AppLanguageMode.ENGLISH,
                    limitEnforcementMode = LimitEnforcementMode.EXTERNAL_BREAK_PAGE,
                ),
            ),
        )
        val breakSession = context.contentResolver.call(
            RuleContract.CONTENT_URI,
            RuleContract.METHOD_CREATE_BREAK_SESSION,
            TARGET_PACKAGE,
            null,
        )
        assertTrue(breakSession?.getBoolean(RuleContract.KEY_OK, false) == true)
        val breakToken = breakSession?.getString(RuleContract.KEY_BREAK_SESSION_TOKEN).orEmpty()
        assertTrue(breakToken.isNotBlank())
        val rule = repository.getRule(TARGET_PACKAGE)

        context.startActivity(
            Intent(context, LimitBlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(LimitBlockActivity.EXTRA_TARGET_PACKAGE, TARGET_PACKAGE)
                putExtra(LimitBlockActivity.EXTRA_BREAK_SESSION_TOKEN, breakToken)
                putExtra(LimitBlockActivity.EXTRA_LAUNCH_ATTEMPT_ID, "instrumented-break-page")
                putExtra(LimitBlockActivity.EXTRA_RULE_VERSION, rule.version)
                putExtra(LimitBlockActivity.EXTRA_GROUP_VERSION, 0L)
                putExtra(LimitBlockActivity.EXTRA_REACHED_KINDS, "APP_DAILY")
                putExtra(LimitBlockActivity.EXTRA_DAY_TOKEN, LocalDate.now().toString())
                putExtra(LimitBlockActivity.EXTRA_ENGLISH, true)
                putExtra(LimitBlockActivity.EXTRA_NON_ROOT, false)
                putExtra(LimitBlockActivity.EXTRA_CONTROL_SESSION_ID, SESSION_ID)
            },
        )
        val unlockEntry = device.wait(
            Until.findObject(By.text("Parent temporary unlock")),
            UI_TIMEOUT_MS,
        )
        assertTrue(unlockEntry != null)
        unlockEntry.click()
        enterPinAndUnlock()

        val override = context.contentResolver.call(
            RuleContract.CONTENT_URI,
            RuleContract.METHOD_HAS_PARENT_OVERRIDE,
            TARGET_PACKAGE,
            Bundle().apply {
                putString(RuleContract.KEY_PROCESS_SESSION_ID, SESSION_ID)
            },
        )
        assertTrue(override?.getBoolean(RuleContract.KEY_OK, false) == true)
        assertTrue(override?.getBoolean(RuleContract.KEY_PARENT_AUTH_GRANTED, false) == true)
    }

    @Test
    fun staleHookRestrictionPageExplainsRestartAndHidesPinEntry() {
        assertTrue(
            repository.saveGlobalSettings(
                repository.getGlobalSettings().copy(
                    childLockEnabled = true,
                    languageMode = AppLanguageMode.ENGLISH,
                    limitEnforcementMode = LimitEnforcementMode.EXTERNAL_BREAK_PAGE,
                ),
            ),
        )
        val breakSession = context.contentResolver.call(
            RuleContract.CONTENT_URI,
            RuleContract.METHOD_CREATE_BREAK_SESSION,
            TARGET_PACKAGE,
            null,
        )
        assertTrue(breakSession?.getBoolean(RuleContract.KEY_OK, false) == true)
        val breakToken = breakSession?.getString(RuleContract.KEY_BREAK_SESSION_TOKEN).orEmpty()
        assertTrue(breakToken.isNotBlank())
        val rule = repository.getRule(TARGET_PACKAGE)

        context.startActivity(
            Intent(context, LimitBlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(LimitBlockActivity.EXTRA_TARGET_PACKAGE, TARGET_PACKAGE)
                putExtra(LimitBlockActivity.EXTRA_BREAK_SESSION_TOKEN, breakToken)
                putExtra(LimitBlockActivity.EXTRA_RULE_VERSION, rule.version)
                putExtra(LimitBlockActivity.EXTRA_GROUP_VERSION, 0L)
                putExtra(LimitBlockActivity.EXTRA_REACHED_KINDS, "APP_DAILY")
                putExtra(LimitBlockActivity.EXTRA_DAY_TOKEN, LocalDate.now().toString())
                putExtra(LimitBlockActivity.EXTRA_ENGLISH, true)
                putExtra(LimitBlockActivity.EXTRA_NON_ROOT, false)
                // Deliberately omit EXTRA_CONTROL_SESSION_ID to model a pre-0.11 Hook process.
            },
        )

        assertTrue(device.wait(Until.hasObject(By.textContains("older Hook")), UI_TIMEOUT_MS))
        assertTrue(device.findObject(By.text("Parent temporary unlock")) == null)
    }

    @Test
    fun missingPrivatePinReturnsSpecificChallengeFailure() {
        assertTrue(childLockRepository.disableAfterAuthentication())
        val challenge = context.contentResolver.call(
            RuleContract.CONTENT_URI,
            RuleContract.METHOD_CREATE_PARENT_AUTH_CHALLENGE,
            TARGET_PACKAGE,
            Bundle().apply {
                putString(RuleContract.KEY_PROCESS_SESSION_ID, SESSION_ID)
                putString(RuleContract.KEY_INCIDENT_ID, "missing-private-pin")
                putString(RuleContract.KEY_PARENT_AUTH_REASON, "QUOTA")
            },
        )

        assertTrue(challenge?.getBoolean(RuleContract.KEY_OK, true) == false)
        assertTrue(challenge?.getString(RuleContract.KEY_MESSAGE) == "child_lock_pin_missing")
    }

    private fun enterPinAndUnlock() {
        assertTrue(device.wait(Until.hasObject(By.text("Parent temporary unlock")), UI_TIMEOUT_MS))
        val pinField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), UI_TIMEOUT_MS)
        assertTrue(pinField != null)
        pinField.text = PIN
        val unlock = device.wait(Until.findObject(By.text("Unlock")), UI_TIMEOUT_MS)
        assertTrue(unlock != null)
        unlock.click()
        assertTrue(
            device.wait(
                Until.gone(By.clazz("android.widget.EditText")),
                UI_TIMEOUT_MS,
            ),
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "com.android.settings"
        const val SESSION_ID = "instrumented-session"
        const val PIN = "2580"
        const val UI_TIMEOUT_MS = 10_000L
    }
}
