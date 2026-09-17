package com.liuml.apptimelimiter.ads

import android.content.Context
import com.liuml.apptimelimiter.core.RewardedAdDecision
import com.liuml.apptimelimiter.core.RewardedAdPolicy
import com.liuml.apptimelimiter.core.RewardedAdQuotaState

class RewardedAdStateRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun isPrivacyConsentGranted(): Boolean = prefs.getBoolean(KEY_PRIVACY_CONSENT, false)

    @Synchronized
    fun setPrivacyConsentGranted(granted: Boolean): Boolean = prefs.edit()
        .putBoolean(KEY_PRIVACY_CONSENT, granted)
        .commit()

    @Synchronized
    fun dailyRemaining(identity: String, dayToken: String): Pair<Int, Long> {
        val prefix = "${identity.take(100)}.daily."
        if (prefs.getString(prefix + KEY_DAY, "") != dayToken) {
            return Int.MAX_VALUE to Long.MAX_VALUE
        }
        return Int.MAX_VALUE to Long.MAX_VALUE
    }

    @Synchronized
    fun claim(
        dailyIdentity: String,
        sessionIdentity: String,
        dayToken: String,
        configuredExtensionMillis: Long,
        ruleRemainingMillis: Long,
        transactionId: String,
    ): RewardedAdDecision {
        val dailyPrefix = "${dailyIdentity.take(100)}.daily."
        val sessionPrefix = "${sessionIdentity.take(100)}.session."
        val state = RewardedAdQuotaState(
            dayToken = prefs.getString(dailyPrefix + KEY_DAY, "").orEmpty(),
            dailyCount = prefs.getInt(dailyPrefix + KEY_DAILY_COUNT, 0),
            dailyExtensionMillis = prefs.getLong(dailyPrefix + KEY_DAILY_MILLIS, 0L),
            sessionCount = prefs.getInt(sessionPrefix + KEY_SESSION_COUNT, 0),
            sessionExtensionMillis = prefs.getLong(sessionPrefix + KEY_SESSION_MILLIS, 0L),
        )
        if (prefs.getString(dailyPrefix + KEY_LAST_TRANSACTION, "") == transactionId) {
            return RewardedAdDecision(false, 0L, state, 0, 0L)
        }
        val decision = RewardedAdPolicy.claim(
            state,
            dayToken.take(32),
            configuredExtensionMillis,
            ruleRemainingMillis,
        )
        if (decision.allowed) {
            check(prefs.edit()
                .putString(dailyPrefix + KEY_DAY, decision.nextState.dayToken)
                .putInt(dailyPrefix + KEY_DAILY_COUNT, decision.nextState.dailyCount)
                .putLong(dailyPrefix + KEY_DAILY_MILLIS, decision.nextState.dailyExtensionMillis)
                .putInt(sessionPrefix + KEY_SESSION_COUNT, decision.nextState.sessionCount)
                .putLong(sessionPrefix + KEY_SESSION_MILLIS, decision.nextState.sessionExtensionMillis)
                .putString(dailyPrefix + KEY_LAST_TRANSACTION, transactionId.take(100))
                .commit()) { "rewarded_ad_state_persist_failed" }
        }
        return decision
    }

    @Synchronized
    fun previewClaim(
        dailyIdentity: String,
        sessionIdentity: String,
        dayToken: String,
        configuredExtensionMillis: Long,
        ruleRemainingMillis: Long,
        transactionId: String,
    ): RewardedAdDecision {
        val dailyPrefix = "${dailyIdentity.take(100)}.daily."
        val sessionPrefix = "${sessionIdentity.take(100)}.session."
        val state = RewardedAdQuotaState(
            dayToken = prefs.getString(dailyPrefix + KEY_DAY, "").orEmpty(),
            dailyCount = prefs.getInt(dailyPrefix + KEY_DAILY_COUNT, 0),
            dailyExtensionMillis = prefs.getLong(dailyPrefix + KEY_DAILY_MILLIS, 0L),
            sessionCount = prefs.getInt(sessionPrefix + KEY_SESSION_COUNT, 0),
            sessionExtensionMillis = prefs.getLong(sessionPrefix + KEY_SESSION_MILLIS, 0L),
        )
        if (prefs.getString(dailyPrefix + KEY_LAST_TRANSACTION, "") == transactionId) {
            return RewardedAdDecision(false, 0L, state, 0, 0L)
        }
        return RewardedAdPolicy.claim(
            state,
            dayToken.take(32),
            configuredExtensionMillis,
            ruleRemainingMillis,
        )
    }

    @Synchronized
    fun resetSession(identity: String): Boolean {
        val prefix = "${identity.take(100)}.session."
        return prefs.edit().remove(prefix + KEY_SESSION_COUNT).remove(prefix + KEY_SESSION_MILLIS).commit()
    }

    /** Reverts a just-claimed transaction when staging its private pending reward fails. */
    @Synchronized
    fun rollbackClaim(
        dailyIdentity: String,
        sessionIdentity: String,
        transactionId: String,
        rewardMillis: Long,
    ): Boolean {
        val dailyPrefix = "${dailyIdentity.take(100)}.daily."
        val sessionPrefix = "${sessionIdentity.take(100)}.session."
        if (prefs.getString(dailyPrefix + KEY_LAST_TRANSACTION, "") != transactionId) {
            return false
        }
        return prefs.edit()
            .putInt(
                dailyPrefix + KEY_DAILY_COUNT,
                (prefs.getInt(dailyPrefix + KEY_DAILY_COUNT, 0) - 1).coerceAtLeast(0),
            )
            .putLong(
                dailyPrefix + KEY_DAILY_MILLIS,
                (prefs.getLong(dailyPrefix + KEY_DAILY_MILLIS, 0L) - rewardMillis).coerceAtLeast(0L),
            )
            .putInt(
                sessionPrefix + KEY_SESSION_COUNT,
                (prefs.getInt(sessionPrefix + KEY_SESSION_COUNT, 0) - 1).coerceAtLeast(0),
            )
            .putLong(
                sessionPrefix + KEY_SESSION_MILLIS,
                (prefs.getLong(sessionPrefix + KEY_SESSION_MILLIS, 0L) - rewardMillis).coerceAtLeast(0L),
            )
            .remove(dailyPrefix + KEY_LAST_TRANSACTION)
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "rewarded_ad_private_state"
        const val KEY_DAY = "day"
        const val KEY_DAILY_COUNT = "daily_count"
        const val KEY_DAILY_MILLIS = "daily_millis"
        const val KEY_SESSION_COUNT = "session_count"
        const val KEY_SESSION_MILLIS = "session_millis"
        const val KEY_LAST_TRANSACTION = "last_transaction"
        const val KEY_PRIVACY_CONSENT = "privacy_consent_granted"
    }
}
