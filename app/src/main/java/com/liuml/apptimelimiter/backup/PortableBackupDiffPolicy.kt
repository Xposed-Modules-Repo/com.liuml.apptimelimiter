package com.liuml.apptimelimiter.backup

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class BackupChange { ADDED, MODIFIED, DELETED, UNCHANGED }
data class BackupFieldDiff(val name: String, val before: String?, val after: String?)
data class BackupEntryDiff(val key: String, val change: BackupChange, val fields: List<BackupFieldDiff>)
data class PortableBackupDiff(
    val rules: List<BackupEntryDiff>,
    val groups: List<BackupEntryDiff>,
    val settings: List<BackupEntryDiff>,
)

/** Only serialized portable fields participate; timestamps and runtime versions do not. */
object PortableBackupDiffPolicy {
    const val STALE_PREVIEW = "configuration_changed_refresh_preview"

    fun isStalePreview(reason: String): Boolean =
        reason.contains(STALE_PREVIEW) || reason.contains("stale_preview")

    fun fingerprint(backup: PortableBackupV1): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(configuration(backup)).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun requireCurrent(expected: String, current: PortableBackupV1) {
        check(expected == fingerprint(current)) { STALE_PREVIEW }
    }

    fun compare(before: PortableBackupV1, after: PortableBackupV1): PortableBackupDiff {
        val old = configuration(before)
        val new = configuration(after)
        return PortableBackupDiff(
            entries(index(old.getJSONArray("rules"), "packageName"), index(new.getJSONArray("rules"), "packageName")),
            entries(index(old.getJSONArray("groups"), "id"), index(new.getJSONArray("groups"), "id")),
            entries(settings(old.getJSONObject("settings")), settings(new.getJSONObject("settings"))),
        )
    }

    private fun configuration(backup: PortableBackupV1): JSONObject =
        // Sort unordered identities only. Import normalization trims/deduplicates quotes;
        // doing that here would hide changes to actual portable field values.
        PortableBackupCodec.bodyToJson(backup.copy(
            rules = backup.rules.sortedBy { it.packageName },
            groups = backup.groups.sortedBy { it.id },
        )).apply {
            remove("createdAtMillis")
            remove("sourceVersionName")
            remove("sourceVersionCode")
        }

    private fun index(array: JSONArray, key: String): Map<String, JSONObject> =
        (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString(key) }

    private fun settings(value: JSONObject): Map<String, JSONObject> =
        value.keys().asSequence().associateWith { JSONObject().put(it, value.get(it)) }

    private fun entries(before: Map<String, JSONObject>, after: Map<String, JSONObject>): List<BackupEntryDiff> =
        (before.keys + after.keys).sorted().map { key ->
            val old = before[key]
            val new = after[key]
            val fields = (old?.keys()?.asSequence()?.toSet().orEmpty() +
                new?.keys()?.asSequence()?.toSet().orEmpty()).sorted().map { field ->
                BackupFieldDiff(field, old?.get(field)?.let(::canonical), new?.get(field)?.let(::canonical))
            }
            val change = when {
                old == null -> BackupChange.ADDED
                new == null -> BackupChange.DELETED
                fields.any { it.before != it.after } -> BackupChange.MODIFIED
                else -> BackupChange.UNCHANGED
            }
            BackupEntryDiff(key, change, fields)
        }

    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
