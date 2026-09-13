package com.liuml.apptimelimiter.backup

import java.util.Base64
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated encryption for the portable backup sent to WebDAV. */
object WebDavCrypto {
    private const val FORMAT = "TSWD1"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 120_000
    const val MAX_PLAINTEXT_BYTES = 2 * 1024 * 1024

    fun encrypt(plaintext: String, password: CharArray, random: SecureRandom = SecureRandom()): String {
        require(password.isNotEmpty()) { "empty_sync_password" }
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "backup_too_large" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(FORMAT.toByteArray(Charsets.US_ASCII))
        val encrypted = cipher.doFinal(bytes)
        return listOf(
            FORMAT,
            Base64.getEncoder().withoutPadding().encodeToString(salt),
            Base64.getEncoder().withoutPadding().encodeToString(iv),
            Base64.getEncoder().withoutPadding().encodeToString(encrypted),
        ).joinToString(".")
    }

    fun decrypt(envelope: String, password: CharArray): String {
        require(password.isNotEmpty()) { "empty_sync_password" }
        val parts = envelope.trim().split('.')
        require(parts.size == 4 && parts[0] == FORMAT) { "invalid_webdav_backup" }
        val salt = Base64.getDecoder().decode(parts[1])
        val iv = Base64.getDecoder().decode(parts[2])
        val encrypted = Base64.getDecoder().decode(parts[3])
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES) { "invalid_webdav_backup" }
        require(encrypted.size <= MAX_PLAINTEXT_BYTES + 32) { "backup_too_large" }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(FORMAT.toByteArray(Charsets.US_ASCII))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            throw IllegalArgumentException("webdav_backup_auth_failed")
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }
}
