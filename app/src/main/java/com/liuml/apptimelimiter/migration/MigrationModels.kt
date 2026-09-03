package com.liuml.apptimelimiter.migration

import org.json.JSONObject

data class MigrationPayload(
    val createdAtMillis: Long,
    val sourceVersionName: String,
    val sourceVersionCode: Int,
    val rulesetGeneration: Long,
    val rules: Map<String, *>,
    val childLock: Map<String, *>,
    val usageStatistics: Map<String, *>,
)

sealed interface MigrationState {
    data object Checking : MigrationState
    data class Ready(val generation: Long, val createdAtMillis: Long) : MigrationState
    data class Imported(val generation: Long, val importedAtMillis: Long) : MigrationState
    data class DirectLegacyImported(val generation: Long, val importedAtMillis: Long) : MigrationState
    data object FreshInstall : MigrationState
    data class Failed(val reason: String) : MigrationState
}

internal object MigrationPayloadPolicy {
    private val childLockKeys = setOf(
        "enabled",
        "salt",
        "verifier",
        "iterations",
        "failed_attempts",
        "lockout_until",
        "biometric_recovery",
    )

    fun validate(payload: MigrationPayload) {
        require(payload.rules.keys.none { it.contains(".runtime_") }) {
            "migration_contains_rule_runtime_state"
        }
        require(payload.usageStatistics.keys.none { key ->
            key.startsWith("heartbeat.") ||
                key.startsWith("hook_version.") ||
                key.startsWith("hook_mode_generation.")
        }) { "migration_contains_hook_runtime_state" }
        require(payload.childLock.keys.all(childLockKeys::contains)) {
            "migration_contains_unknown_child_lock_state"
        }
    }
}

internal object MigrationPayloadCodec {
    const val SCHEMA = 1

    fun encode(payload: MigrationPayload): ByteArray {
        MigrationPayloadPolicy.validate(payload)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("createdAtMillis", payload.createdAtMillis)
            .put("sourceVersionName", payload.sourceVersionName)
            .put("sourceVersionCode", payload.sourceVersionCode)
            .put("rulesetGeneration", payload.rulesetGeneration)
            .put("rules", PreferenceMapCodec.encode(payload.rules))
            .put("childLock", PreferenceMapCodec.encode(payload.childLock))
            .put("usageStatistics", PreferenceMapCodec.encode(payload.usageStatistics))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): MigrationPayload {
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "migration_payload_too_large" }
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.getInt("schema") == SCHEMA) { "unsupported_migration_schema" }
        return MigrationPayload(
            createdAtMillis = value.getLong("createdAtMillis").also {
                require(it > 0L) { "invalid_migration_created_at" }
            },
            sourceVersionName = value.getString("sourceVersionName").also {
                require(it.isNotBlank() && it.length <= 80) { "invalid_migration_version" }
            },
            sourceVersionCode = value.getInt("sourceVersionCode").also {
                require(it in 1..52) { "invalid_migration_version_code" }
            },
            rulesetGeneration = value.getLong("rulesetGeneration").also {
                require(it >= 0L) { "invalid_migration_generation" }
            },
            rules = PreferenceMapCodec.decode(value.getJSONObject("rules")),
            childLock = PreferenceMapCodec.decode(value.getJSONObject("childLock")),
            usageStatistics = PreferenceMapCodec.decode(value.getJSONObject("usageStatistics")),
        ).also(MigrationPayloadPolicy::validate)
    }

    private const val MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024
}
