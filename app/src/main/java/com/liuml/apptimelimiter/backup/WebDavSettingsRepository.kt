package com.liuml.apptimelimiter.backup

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedWebDavConfig(
    val endpoint: String = "",
    val username: String = "",
    val password: String = "",
    val syncPassword: String = "",
    val autoSync: Boolean = false,
)

class WebDavSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("webdav_settings", Context.MODE_PRIVATE)

    fun load(): SavedWebDavConfig {
        val encoded = prefs.getString(KEY_CREDENTIALS, null) ?: return SavedWebDavConfig(
            endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
            autoSync = prefs.getBoolean(KEY_AUTO_SYNC, false),
        )
        return runCatching {
            val value = JSONObject(decrypt(encoded))
            SavedWebDavConfig(
                endpoint = value.optString("endpoint"),
                username = value.optString("username"),
                password = value.optString("password"),
                syncPassword = value.optString("syncPassword"),
                autoSync = prefs.getBoolean(KEY_AUTO_SYNC, false),
            )
        }.getOrDefault(SavedWebDavConfig())
    }

    fun save(config: SavedWebDavConfig): Boolean = runCatching {
        val plaintext = JSONObject()
            .put("endpoint", config.endpoint.trim())
            .put("username", config.username)
            .put("password", config.password)
            .put("syncPassword", config.syncPassword)
            .toString()
        prefs.edit()
            .putString(KEY_CREDENTIALS, encrypt(plaintext))
            .putBoolean(KEY_AUTO_SYNC, config.autoSync)
            .commit()
    }.getOrDefault(false)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOf(12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }

    private companion object {
        const val KEY_ALIAS = "TimeStopWebDavCredentials"
        const val KEY_CREDENTIALS = "encrypted_credentials"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_AUTO_SYNC = "auto_sync"
    }
}
