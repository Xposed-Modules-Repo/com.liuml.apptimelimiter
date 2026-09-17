package com.liuml.apptimelimiter.backup

import android.content.Context
import android.util.Base64
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.data.RuleRepository
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

data class WebDavConfig(
    val endpoint: String,
    val username: String,
    val password: String,
    val syncPassword: CharArray,
)

sealed interface WebDavResult {
    data class Success(val remoteCreatedAtMillis: Long? = null) : WebDavResult
    data class Conflict(val local: PortableBackupV1, val remote: PortableBackupV1) : WebDavResult
    data class Failure(val reason: String) : WebDavResult
}

/** Network work is intentionally explicit and must be called off the UI thread. */
class WebDavRepository(context: Context) {
    private val appContext = context.applicationContext
    private val rules = RuleRepository(appContext)

    fun upload(config: WebDavConfig): WebDavResult = synchronized(UPLOAD_LOCK) {
        runCatching {
            val backup = rules.exportPortableBackup(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            val plaintext = PortableBackupCodec.encode(backup)
            val encrypted = WebDavCrypto.encrypt(plaintext, config.syncPassword)
                .toByteArray(StandardCharsets.UTF_8)
            val temporaryEndpoint = temporaryEndpoint(config.endpoint)
            putPayload(config, temporaryEndpoint, encrypted)
            try {
                move(config, temporaryEndpoint)
            } catch (moveFailure: Throwable) {
                // Some Android HttpURLConnection implementations reject MOVE. Keep the
                // compatible final PUT fallback, while never exposing a partial final file.
                delete(config, temporaryEndpoint)
                putPayload(config, config.endpoint, encrypted)
            }
            WebDavResult.Success(backup.createdAtMillis)
        }.getOrElse { WebDavResult.Failure(it.message ?: "webdav_upload_failed") }
    }

    private fun putPayload(config: WebDavConfig, endpoint: String, payload: ByteArray) {
        val connection = open(config, endpoint, "PUT")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { it.write(payload) }
            check(connection.responseCode in 200..299) { "webdav_http_${connection.responseCode}" }
        } finally {
            connection.disconnect()
        }
    }

    private fun move(config: WebDavConfig, temporaryEndpoint: String) {
        val connection = open(config, temporaryEndpoint, "MOVE")
        try {
            connection.setRequestProperty("Destination", config.endpoint)
            connection.setRequestProperty("Overwrite", "T")
            check(connection.responseCode in 200..299) { "webdav_move_http_${connection.responseCode}" }
        } finally {
            connection.disconnect()
        }
    }

    private fun delete(config: WebDavConfig, endpoint: String) {
        runCatching {
            val connection = open(config, endpoint, "DELETE")
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }

    fun download(config: WebDavConfig): Result<PortableBackupV1> = runCatching {
        val connection = open(config, config.endpoint, "GET")
        try {
            check(connection.responseCode in 200..299) { "webdav_http_${connection.responseCode}" }
            val text = connection.inputStream.use { input ->
                val maxBytes = WebDavCrypto.MAX_PLAINTEXT_BYTES * 2
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "backup_too_large" }
                    bytes.write(buffer, 0, read)
                }
                String(bytes.toByteArray(), StandardCharsets.UTF_8)
            }
            val decoded = PortableBackupCodec.decode(WebDavCrypto.decrypt(text, config.syncPassword))
            when (val validation = PortableBackupPolicy.validate(decoded, appContext.packageName)) {
                is PortableBackupValidationResult.Valid -> validation.backup
                is PortableBackupValidationResult.Invalid -> error(validation.reason)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(config: WebDavConfig, endpoint: String, method: String): HttpURLConnection {
        val uri = URI(endpoint.trim())
        require(uri.scheme.equals("https", true)) { "webdav_https_required" }
        require(uri.userInfo == null && uri.host.isNullOrBlank().not()) { "invalid_webdav_url" }
        return (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
            readTimeout = TimeUnit.SECONDS.toMillis(20).toInt()
            useCaches = false
            // Credentials belong only to the explicitly configured endpoint.
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "TimeStop/${BuildConfig.VERSION_NAME}")
            val credentials = "${config.username}:${config.password}"
                .toByteArray(StandardCharsets.UTF_8)
            setRequestProperty("Authorization", "Basic ${Base64.encodeToString(credentials, Base64.NO_WRAP)}")
        }
    }

    private fun temporaryEndpoint(endpoint: String): String {
        val uri = URI(endpoint.trim())
        val path = (uri.path ?: "") + ".timestop-${UUID.randomUUID()}"
        return URI(uri.scheme, uri.rawAuthority, path, uri.rawQuery, uri.rawFragment).toString()
    }

    private companion object {
        val UPLOAD_LOCK = Any()
    }
}

internal fun webDavDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
