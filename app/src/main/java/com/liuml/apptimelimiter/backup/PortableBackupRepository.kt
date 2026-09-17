package com.liuml.apptimelimiter.backup

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.data.InstalledAppsRepository
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import java.io.ByteArrayOutputStream
import java.io.File

sealed interface PortableBackupOperationResult {
    data class Success(val message: String) : PortableBackupOperationResult
    data class Failure(val reason: String) : PortableBackupOperationResult
}

class PortableBackupRepository(context: Context) {
    private val appContext = context.applicationContext
    private val rules = RuleRepository(appContext)
    private val diagnostics = DiagnosticsRepository(appContext)
    private val rollbackFile = AtomicFile(
        File(appContext.noBackupFilesDir, "portable-backup/last-import-rollback.json").apply {
            parentFile?.mkdirs()
        },
    )

    fun export(uri: Uri): PortableBackupOperationResult = runCatching {
        val backup = rules.exportPortableBackup(
            sourceVersionName = BuildConfig.VERSION_NAME,
            sourceVersionCode = BuildConfig.VERSION_CODE,
        )
        val encoded = PortableBackupCodec.encode(backup)
        appContext.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
            writer.write(encoded)
            writer.flush()
        } ?: error("open_output_failed")
        diagnostics.append(
            "INFO",
            appContext.packageName,
            "PORTABLE_BACKUP_EXPORTED",
            "rules=${backup.rules.size}, groups=${backup.groups.size}",
        )
        PortableBackupOperationResult.Success("exported")
    }.getOrElse { error ->
        diagnostics.append(
            "ERROR",
            appContext.packageName,
            "PORTABLE_BACKUP_EXPORT_FAILED",
            errorSummary(error),
        )
        PortableBackupOperationResult.Failure(errorSummary(error))
    }

    fun preview(uri: Uri): Result<PortableBackupPreview> = runCatching {
        preview(PortableBackupCodec.decode(readBounded(uri))).getOrThrow()
    }

    fun preview(backup: PortableBackupV1): Result<PortableBackupPreview> = runCatching {
        when (val validation = PortableBackupPolicy.validate(backup, appContext.packageName)) {
            is PortableBackupValidationResult.Invalid -> error(validation.reason)
            is PortableBackupValidationResult.Valid -> Unit
        }
        val target = PortableBackupPolicy.normalize(backup)
        val installed = InstalledAppsRepository(appContext).loadLaunchableApps()
            .mapTo(mutableSetOf()) { it.packageName }
        val packages = (target.rules.map { it.packageName } +
            target.groups.flatMap { it.packageNames }).toSet()
        val current = currentConfiguration()
        PortableBackupPreview(
            backup = target,
            installedRuleCount = packages.count(installed::contains),
            missingRulePackages = packages - installed,
            existingRuleCount = current.rules.size,
            existingGroupCount = current.groups.size,
            currentFingerprint = PortableBackupDiffPolicy.fingerprint(current),
            diff = PortableBackupDiffPolicy.compare(current, target),
        )
    }

    fun import(preview: PortableBackupPreview): PortableBackupOperationResult = runCatching {
        when (
            val validation = PortableBackupPolicy.validate(
                preview.backup,
                appContext.packageName,
            )
        ) {
            is PortableBackupValidationResult.Invalid -> error(validation.reason)
            is PortableBackupValidationResult.Valid -> Unit
        }
        check(rules.compareAndReplacePortableConfiguration(preview.backup, preview.currentFingerprint) { current ->
            writeRollback(PortableBackupCodec.encode(current))
        }) {
            "replace_configuration_failed"
        }
        rules.reconcileRuleAccess()
        diagnostics.append(
            "INFO",
            appContext.packageName,
            "PORTABLE_BACKUP_IMPORTED",
            "rules=${preview.backup.rules.size}, groups=${preview.backup.groups.size}, missing=${preview.missingRulePackages.size}",
        )
        PortableBackupOperationResult.Success("imported")
    }.getOrElse { error ->
        diagnostics.append(
            "ERROR",
            appContext.packageName,
            "PORTABLE_BACKUP_IMPORT_FAILED",
            errorSummary(error),
        )
        PortableBackupOperationResult.Failure(errorSummary(error))
    }

    fun missingConfiguredPackages(): Set<String> {
        val installed = InstalledAppsRepository(appContext).loadLaunchableApps()
            .mapTo(mutableSetOf()) { it.packageName }
        return rules.configuredPackages() - installed
    }

    private fun currentConfiguration() = rules.exportPortableBackup(
        sourceVersionName = BuildConfig.VERSION_NAME,
        sourceVersionCode = BuildConfig.VERSION_CODE,
        includeInactiveRules = true,
    )

    private fun readBounded(uri: Uri): String {
        val input = appContext.contentResolver.openInputStream(uri) ?: error("open_input_failed")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= PortableBackupPolicy.MAX_BACKUP_BYTES) { "backup_too_large" }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun writeRollback(value: String) {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = rollbackFile.startWrite()
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            rollbackFile.finishWrite(stream)
        } catch (error: Throwable) {
            stream?.let(rollbackFile::failWrite)
            throw error
        }
    }

    private fun errorSummary(error: Throwable): String =
        "${error.javaClass.simpleName}:${error.message.orEmpty()}".take(240)
}
