package com.liuml.apptimelimiter.security

import android.content.Context
import android.util.Base64
import com.liuml.apptimelimiter.core.PinAttemptDecision
import com.liuml.apptimelimiter.core.PinAttemptPolicy
import com.liuml.apptimelimiter.core.PinAttemptState
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class ChildLockSnapshot(
    val enabled: Boolean,
    val biometricRecoveryEnabled: Boolean,
    val failedAttempts: Int,
    val lockoutUntilMillis: Long,
)

sealed interface PinVerificationResult {
    data object Success : PinVerificationResult
    data class Rejected(val remainingMillis: Long) : PinVerificationResult
    data class Locked(val remainingMillis: Long) : PinVerificationResult
    data object NotConfigured : PinVerificationResult
    data class Error(val cause: String) : PinVerificationResult
}

/** Private credential store. Nothing in this class is mirrored to XSharedPreferences. */
class ChildLockRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun snapshot(): ChildLockSnapshot = synchronized(STORE_LOCK) {
        ChildLockSnapshot(
            enabled = isEnabled(),
            biometricRecoveryEnabled = prefs.getBoolean(KEY_BIOMETRIC_RECOVERY, false),
            failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0).coerceAtLeast(0),
            lockoutUntilMillis = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L).coerceAtLeast(0L),
        )
    }

    fun isEnabled(): Boolean =
        prefs.getBoolean(KEY_ENABLED, false) &&
            prefs.contains(KEY_SALT) && prefs.contains(KEY_VERIFIER)

    fun isValidPinFormat(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all(Char::isDigit)

    fun enable(pin: String): Boolean = synchronized(STORE_LOCK) {
        require(isValidPinFormat(pin)) { "PIN must contain 4-8 digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val verifier = runCatching { derive(pin, salt, ITERATIONS) }
            .getOrElse { return@synchronized false }
        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_VERIFIER, encode(verifier))
            .putInt(KEY_ITERATIONS, ITERATIONS)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .commit()
    }

    fun verify(
        pin: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): PinVerificationResult = synchronized(STORE_LOCK) {
        if (!isEnabled()) return@synchronized PinVerificationResult.NotConfigured
        if (!isValidPinFormat(pin)) return@synchronized PinVerificationResult.Rejected(0L)
        val storedState = PinAttemptState(
            failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0).coerceAtLeast(0),
            lockoutUntilMillis = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L).coerceAtLeast(0L),
        )
        val state = PinAttemptPolicy.normalize(storedState, nowMillis)
        if (state != storedState) {
            if (!persistState(state)) {
                return@synchronized PinVerificationResult.Error("state_persist_failed")
            }
        }
        if (PinAttemptPolicy.remainingMillis(state, nowMillis) > 0L) {
            return@synchronized PinVerificationResult.Locked(
                PinAttemptPolicy.remainingMillis(state, nowMillis),
            )
        }
        val salt = decode(prefs.getString(KEY_SALT, null))
            ?: return@synchronized PinVerificationResult.NotConfigured
        val expected = decode(prefs.getString(KEY_VERIFIER, null))
            ?: return@synchronized PinVerificationResult.NotConfigured
        val iterations = prefs.getInt(KEY_ITERATIONS, ITERATIONS).coerceIn(50_000, ITERATIONS)
        val actual = runCatching { derive(pin, salt, iterations) }.getOrElse { error ->
            return@synchronized PinVerificationResult.Error(
                error.javaClass.simpleName.take(80),
            )
        }
        val decision = PinAttemptPolicy.evaluate(
            state = state,
            pinMatches = MessageDigest.isEqual(expected, actual),
            nowMillis = nowMillis,
        )
        if (!persistAttempt(decision)) {
            return@synchronized PinVerificationResult.Error("attempt_persist_failed")
        }
        when {
            decision.accepted -> PinVerificationResult.Success
            decision.locked -> PinVerificationResult.Locked(decision.remainingMillis)
            else -> PinVerificationResult.Rejected(decision.remainingMillis)
        }
    }

    fun replacePinAfterAuthentication(newPin: String): Boolean = enable(newPin)

    fun disableAfterAuthentication(): Boolean = synchronized(STORE_LOCK) {
        prefs.edit().clear().commit()
    }

    fun setBiometricRecoveryEnabledAfterAuthentication(enabled: Boolean): Boolean =
        synchronized(STORE_LOCK) {
            prefs.edit().putBoolean(KEY_BIOMETRIC_RECOVERY, enabled).commit()
        }

    private fun persistAttempt(decision: PinAttemptDecision): Boolean =
        persistState(decision.state)

    private fun persistState(state: PinAttemptState): Boolean =
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, state.failedAttempts)
            .putLong(KEY_LOCKOUT_UNTIL, state.lockoutUntilMillis)
            .commit()

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String?): ByteArray? = value?.let {
        runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
    }

    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
        private const val PREFS_NAME = "child_lock_private"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SALT = "salt"
        private const val KEY_VERIFIER = "verifier"
        private const val KEY_ITERATIONS = "iterations"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_BIOMETRIC_RECOVERY = "biometric_recovery"
        private const val SALT_BYTES = 24
        private const val KEY_BITS = 256
        private const val ITERATIONS = 210_000
        private val STORE_LOCK = Any()
    }
}
