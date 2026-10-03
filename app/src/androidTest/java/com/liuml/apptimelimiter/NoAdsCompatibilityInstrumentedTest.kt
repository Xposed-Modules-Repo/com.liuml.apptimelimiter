package com.liuml.apptimelimiter

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.liuml.apptimelimiter.ipc.RuleContract
import org.junit.Assert.*
import org.junit.Test

/** Read-only checks against the installed manager; never changes rules, PIN or quota. */
class NoAdsCompatibilityInstrumentedTest {
    @Test fun legacyAdvertisingMethodsAlwaysReject() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        listOf(
            RuleContract.METHOD_CHECK_REWARDED_AD_ELIGIBILITY,
            RuleContract.METHOD_CLAIM_REWARDED_AD,
            RuleContract.METHOD_CONSUME_REWARDED_AD,
            RuleContract.METHOD_RESET_REWARDED_AD_SESSION,
            RuleContract.METHOD_MARK_PARENT_AUTH_VERIFIED_FOR_AD,
            RuleContract.METHOD_MARK_PARENT_AUTH_AD_REWARDED,
        ).forEach { method ->
            val response = resolver.call(RuleContract.CONTENT_URI, method, "invalid", Bundle())!!
            assertFalse(method, response.getBoolean(RuleContract.KEY_OK))
            assertEquals(method, "ads_disabled", response.getString(RuleContract.KEY_MESSAGE))
        }
    }

    @Test fun buildContainsNoAdCredentialsOrSdkClasses() {
        assertFalse(BuildConfig::class.java.declaredFields.any { it.name.startsWith("TOPON_") })
        listOf("com.thinkup.core.api.TUSDK", "com.anythink.core.api.ATSDK").forEach { name ->
            assertTrue(runCatching { Class.forName(name) }.exceptionOrNull() is ClassNotFoundException)
        }
    }
}
