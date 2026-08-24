package com.liuml.apptimelimiter.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

sealed interface BiometricRecoveryResult {
    data object Success : BiometricRecoveryResult
    data object Unavailable : BiometricRecoveryResult
    data object Invalidated : BiometricRecoveryResult
    data object Cancelled : BiometricRecoveryResult
    data class Failed(val error: String) : BiometricRecoveryResult
}

class BiometricRecoveryManager(context: Context) {
    private val appContext = context.applicationContext
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun canUseStrongBiometric(): Boolean = BiometricManager.from(appContext).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG,
    ) == BiometricManager.BIOMETRIC_SUCCESS

    fun createRecoveryKey(): Boolean {
        if (!canUseStrongBiometric()) return false
        return runCatching {
            keyStore.deleteEntry(KEY_ALIAS)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setUserAuthenticationParameters(
                                0,
                                KeyProperties.AUTH_BIOMETRIC_STRONG,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            setUserAuthenticationValidityDurationSeconds(-1)
                        }
                    }
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            generator.generateKey()
            true
        }.getOrDefault(false)
    }

    fun deleteRecoveryKey() {
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onResult: (BiometricRecoveryResult) -> Unit,
    ) {
        if (!canUseStrongBiometric()) {
            onResult(BiometricRecoveryResult.Unavailable)
            return
        }
        val cipher = try {
            createCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            onResult(BiometricRecoveryResult.Invalidated)
            return
        } catch (error: Throwable) {
            onResult(BiometricRecoveryResult.Failed(error.javaClass.simpleName))
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    onResult(
                        if (cancelled) BiometricRecoveryResult.Cancelled
                        else BiometricRecoveryResult.Failed("$errorCode:$errString"),
                    )
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedCipher = result.cryptoObject?.cipher
                    val verified = runCatching {
                        authenticatedCipher?.doFinal(PROOF) != null
                    }.getOrDefault(false)
                    onResult(
                        if (verified) BiometricRecoveryResult.Success
                        else BiometricRecoveryResult.Failed("crypto_proof_failed"),
                    )
                }

                override fun onAuthenticationFailed() = Unit
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(
                if (activity.resources.configuration.locales[0]?.language == "en") {
                    "Cancel"
                } else {
                    "取消"
                },
            )
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun createCipher(): Cipher {
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("missing_recovery_key")
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "time_stop_child_lock_recovery_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val PROOF = "time-stop-recovery-proof".toByteArray(Charsets.UTF_8)
    }
}
