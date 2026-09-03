package com.liuml.apptimelimiter.migration

import android.content.Context
import android.util.AtomicFile
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.security.ChildLockRepository
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import java.io.File
import java.util.concurrent.TimeUnit

class MigrationCoordinator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val statePrefs = appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
    private val migrationFile = AtomicFile(
        File(appContext.noBackupFilesDir, "migration/legacy-modern-v1.bin").apply {
            parentFile?.mkdirs()
        },
    )

    @Volatile
    var state: MigrationState = MigrationState.Checking
        private set

    fun initialize(): MigrationState = synchronized(LOCK) {
        state = when {
            BuildConfig.LEGACY_MIGRATION_EXPORT_ENABLED -> prepareLegacyExport()
            BuildConfig.MODERN_XPOSED_ENABLED -> importForModern()
            else -> MigrationState.FreshInstall
        }
        cleanupConsumedCapsule()
        recordState(state)
        state
    }

    fun refreshLegacyExport(): MigrationState = synchronized(LOCK) {
        if (!BuildConfig.LEGACY_MIGRATION_EXPORT_ENABLED) return@synchronized state
        prepareLegacyExport().also {
            state = it
            recordState(it)
        }
    }

    fun canInitializeRepositories(): Boolean = when (state) {
        is MigrationState.Imported,
        is MigrationState.DirectLegacyImported,
        is MigrationState.Ready,
        MigrationState.FreshInstall -> true
        MigrationState.Checking,
        is MigrationState.Failed -> false
    }

    private fun prepareLegacyExport(): MigrationState {
        return runCatching {
            val rules = RuleRepository(appContext)
            require(rules.isLegacyMigrationAuthorityConfirmed()) {
                "legacy_authority_unavailable"
            }
            val payload = MigrationPayload(
                createdAtMillis = System.currentTimeMillis(),
                sourceVersionName = BuildConfig.VERSION_NAME,
                sourceVersionCode = BuildConfig.VERSION_CODE,
                rulesetGeneration = rules.rulesetGeneration(),
                rules = rules.exportMigrationSnapshot(),
                childLock = ChildLockRepository(appContext).exportMigrationSnapshot(),
                usageStatistics = UsageStatsRepository(appContext).exportMigrationSnapshot(),
            )
            val encrypted = MigrationCrypto.encrypt(MigrationPayloadCodec.encode(payload))
            writeAtomic(encrypted)
            val verified = MigrationPayloadCodec.decode(
                MigrationCrypto.decrypt(migrationFile.readFully()),
            )
            require(verified.rulesetGeneration == payload.rulesetGeneration) {
                "migration_generation_mismatch"
            }
            check(
                statePrefs.edit()
                    .putBoolean(KEY_READY, true)
                    .putLong(KEY_GENERATION, payload.rulesetGeneration)
                    .putLong(KEY_CREATED_AT, payload.createdAtMillis)
                    .remove(KEY_LAST_ERROR)
                    .commit(),
            ) { "migration_state_commit_failed" }
            MigrationState.Ready(payload.rulesetGeneration, payload.createdAtMillis)
        }.getOrElse { error ->
            statePrefs.edit()
                .putString(KEY_LAST_ERROR, errorSummary(error))
                .commit()
            MigrationState.Failed(errorSummary(error))
        }
    }

    private fun importForModern(): MigrationState {
        if (statePrefs.getBoolean(KEY_IMPORTED, false)) {
            return MigrationState.Imported(
                generation = statePrefs.getLong(KEY_IMPORTED_GENERATION, 0L),
                importedAtMillis = statePrefs.getLong(KEY_IMPORTED_AT, 0L),
            )
        }
        if (!migrationFile.baseFile.exists()) {
            return if (isFreshInstall()) {
                if (
                    statePrefs.edit()
                        .putBoolean(MigrationStorageGate.KEY_FRESH_INSTALL, true)
                        .remove(KEY_LAST_ERROR)
                        .commit()
                ) {
                    MigrationState.FreshInstall
                } else {
                    MigrationState.Failed("migration_fresh_install_state_commit_failed")
                }
            } else if (RuleRepository.hadPriorRuleStorage(appContext)) {
                directLegacyImport()
            } else {
                MigrationState.Failed("migration_capsule_missing")
            }
        }
        return runCatching {
            val payload = MigrationPayloadCodec.decode(
                MigrationCrypto.decrypt(migrationFile.readFully()),
            )
            val rules = RuleRepository(appContext)
            val childLock = ChildLockRepository(appContext)
            val statistics = UsageStatsRepository(appContext)
            val oldRules = rules.rawAuthoritativeSnapshot()
            val oldChildLock = childLock.exportMigrationSnapshot()
            val oldStatistics = statistics.rawMigrationStorageSnapshot()
            fun rollback() {
                rules.replaceRawAuthoritativeSnapshot(oldRules)
                childLock.importMigrationSnapshot(oldChildLock)
                statistics.importMigrationSnapshot(oldStatistics)
            }
            try {
                check(
                    rules.replaceRawAuthoritativeSnapshot(payload.rules) &&
                        childLock.importMigrationSnapshot(payload.childLock) &&
                        statistics.importMigrationSnapshot(payload.usageStatistics),
                ) { "migration_import_commit_failed" }
                check(rules.synchronizeAuthoritativeMirrorForMigration()) {
                    "migration_remote_preferences_sync_failed"
                }
                val importedAt = System.currentTimeMillis()
                val importedGeneration = rules.rulesetGeneration()
                check(
                    statePrefs.edit()
                        .putBoolean(KEY_IMPORTED, true)
                        .putLong(KEY_IMPORTED_GENERATION, importedGeneration)
                        .putLong(KEY_IMPORTED_AT, importedAt)
                        .putLong(KEY_DELETE_AFTER, importedAt + RETENTION_MILLIS)
                        .remove(KEY_LAST_ERROR)
                        .commit(),
                ) { "migration_import_state_commit_failed" }
                MigrationState.Imported(importedGeneration, importedAt)
            } catch (error: Throwable) {
                rollback()
                rules.synchronizeAuthoritativeMirrorForMigration()
                statePrefs.edit()
                    .remove(KEY_IMPORTED)
                    .remove(KEY_IMPORTED_GENERATION)
                    .remove(KEY_IMPORTED_AT)
                    .remove(KEY_DELETE_AFTER)
                    .commit()
                throw error
            }
        }.getOrElse { error ->
            statePrefs.edit().putString(KEY_LAST_ERROR, errorSummary(error)).commit()
            MigrationState.Failed(errorSummary(error))
        }
    }

    /**
     * Supports users who install Modern directly over a legacy build without first installing
     * the transition APK. RuleRepository has already adopted the confirmed legacy store; this
     * marker only opens the Modern mirror gate and makes the one-time path observable.
     */
    private fun directLegacyImport(): MigrationState {
        return runCatching {
            val rules = RuleRepository(appContext)
            require(rules.isLegacyMigrationAuthorityConfirmed()) {
                "direct_legacy_authority_unavailable"
            }
            val generation = rules.rulesetGeneration()
            val importedAt = System.currentTimeMillis()
            check(rules.synchronizeAuthoritativeMirrorForMigration()) {
                "direct_legacy_remote_preferences_sync_failed"
            }
            check(
                statePrefs.edit()
                    .putBoolean(MigrationStorageGate.KEY_DIRECT_LEGACY_IMPORTED, true)
                    .putLong(KEY_IMPORTED_GENERATION, generation)
                    .putLong(KEY_IMPORTED_AT, importedAt)
                    .remove(KEY_LAST_ERROR)
                    .commit(),
            ) { "direct_legacy_state_commit_failed" }
            MigrationState.DirectLegacyImported(generation, importedAt)
        }.getOrElse { error ->
            statePrefs.edit().putString(KEY_LAST_ERROR, errorSummary(error)).commit()
            MigrationState.Failed(errorSummary(error))
        }
    }

    private fun isFreshInstall(): Boolean = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        kotlin.math.abs(info.lastUpdateTime - info.firstInstallTime) < FRESH_INSTALL_TOLERANCE_MILLIS
    }.getOrDefault(false)

    private fun writeAtomic(bytes: ByteArray) {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = migrationFile.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            migrationFile.finishWrite(stream)
        } catch (error: Throwable) {
            stream?.let(migrationFile::failWrite)
            throw error
        }
    }

    private fun cleanupConsumedCapsule() {
        val deleteAfter = statePrefs.getLong(KEY_DELETE_AFTER, Long.MAX_VALUE)
        if (statePrefs.getBoolean(KEY_IMPORTED, false) && System.currentTimeMillis() >= deleteAfter) {
            migrationFile.delete()
            statePrefs.edit().remove(KEY_DELETE_AFTER).commit()
        }
    }

    private fun errorSummary(error: Throwable): String =
        "${error.javaClass.simpleName}:${error.message.orEmpty()}".take(240)

    private fun recordState(value: MigrationState) {
        val event = when (value) {
            is MigrationState.Ready -> "LEGACY_MIGRATION_READY"
            is MigrationState.Imported -> "MODERN_MIGRATION_IMPORTED"
            is MigrationState.DirectLegacyImported -> "MODERN_DIRECT_LEGACY_IMPORTED"
            is MigrationState.Failed -> "MIGRATION_FAILED"
            MigrationState.FreshInstall -> "MIGRATION_FRESH_INSTALL"
            MigrationState.Checking -> "MIGRATION_CHECKING"
        }
        runCatching {
            DiagnosticsRepository(appContext).appendRateLimited(
                level = if (value is MigrationState.Failed) "ERROR" else "INFO",
                packageName = appContext.packageName,
                event = event,
                stateSignature = value.toString(),
                message = when (value) {
                    is MigrationState.Ready -> "generation=${value.generation}"
                    is MigrationState.Imported -> "generation=${value.generation}"
                    is MigrationState.DirectLegacyImported -> "generation=${value.generation}"
                    is MigrationState.Failed -> "reason=${value.reason}"
                    else -> "state=$value"
                },
            )
        }
    }

    companion object {
        private const val STATE_PREFS = MigrationStorageGate.STATE_PREFS
        private const val KEY_READY = "ready"
        private const val KEY_GENERATION = "generation"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_IMPORTED = "imported"
        private const val KEY_IMPORTED_GENERATION = "imported_generation"
        private const val KEY_IMPORTED_AT = "imported_at"
        private const val KEY_DELETE_AFTER = "delete_after"
        private const val KEY_LAST_ERROR = "last_error"
        private val RETENTION_MILLIS = TimeUnit.DAYS.toMillis(30)
        private const val FRESH_INSTALL_TOLERANCE_MILLIS = 5_000L
        private val LOCK = Any()

        @Volatile
        private var instance: MigrationCoordinator? = null

        fun get(context: Context): MigrationCoordinator = instance ?: synchronized(this) {
            instance ?: MigrationCoordinator(context).also { instance = it }
        }
    }
}
