package com.liuml.apptimelimiter.migration

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object MigrationCrypto {
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        return JSONObject()
            .put("format", FORMAT)
            .put("schema", 1)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put(
                "ciphertext",
                Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size <= MAX_ENVELOPE_BYTES) { "migration_envelope_too_large" }
        val value = JSONObject(envelope.toString(Charsets.UTF_8))
        require(value.getString("format") == FORMAT && value.getInt("schema") == 1) {
            "unsupported_migration_envelope"
        }
        val iv = Base64.decode(value.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(value.getString("ciphertext"), Base64.NO_WRAP)
        require(iv.size == 12 && ciphertext.isNotEmpty()) { "invalid_migration_envelope" }
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            updateAAD(AAD)
            doFinal(ciphertext)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private const val FORMAT = "time-stop-legacy-modern-migration"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "time_stop_legacy_modern_migration_v1"
    private val AAD = "com.liuml.apptimelimiter:migration:1".toByteArray(Charsets.UTF_8)
    private const val MAX_ENVELOPE_BYTES = 6 * 1024 * 1024
}
