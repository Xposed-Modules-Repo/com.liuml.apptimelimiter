package com.liuml.apptimelimiter.security

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RuntimeDiagnosticEvent(val id: Long, val eventKey: String, val packageName: String,
    val incidentId: String, val stage: String, val result: String, val reason: String, val wallMillis: Long)

enum class RuntimeDiagnosticStage { CLAIM, TRANSITION, PIN_ISSUED, PIN_CONSUMED, PIN_COMPLETED,
    PIN_WAITING_AD, PIN_REWARDED, PIN_TIMEOUT, PIN_ACTIVATED, PIN_REVOKED, PIN_CLEARED,
    EVALUATING, LIMIT_CLAIMED, WAITING_PARENT_AUTH, WAITING_AD, OVERRIDE_PENDING, OVERRIDE_ACTIVE,
    EXECUTING_RESTRICTION, RESTRICTION_VISIBLE, COOLDOWN_ACTIVE, CANCELLED }
enum class RuntimeDiagnosticResult { ACCEPTED, DENIED }
enum class RuntimeDiagnosticReason { NONE, EXPIRED, CANCELLED }

/** Manager-private authority. No PIN material and no writes to legacy preferences after migration. */
class ManagerControlDatabase internal constructor(context: Context, name: String = "manager_control_v1.db",
    migrationReady: () -> Boolean = {
        !com.liuml.apptimelimiter.BuildConfig.MODERN_XPOSED_ENABLED ||
            com.liuml.apptimelimiter.migration.MigrationCoordinator.get(context).canInitializeRepositories()
    },
    private val diagnosticsEnabled: () -> Boolean = {
        runCatching { com.liuml.apptimelimiter.data.RuleRepository(context).getGlobalSettings().diagnosticsEnabled }.getOrDefault(false)
    }) {
    // Must precede both repository-backed callbacks and opening/migrating the SQLite file.
    init { check(migrationReady()) { "Control database migration gate is closed" } }
    private val helper = object : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE state (namespace TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(namespace,key))")
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE diagnostic_outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, event_key TEXT NOT NULL UNIQUE, package_name TEXT NOT NULL, incident_id TEXT NOT NULL, stage TEXT NOT NULL, result TEXT NOT NULL, reason TEXT NOT NULL, wall_ms INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported control schema")
    }
    private val db get() = helper.writableDatabase
    @Volatile private var diagnosticsAllowed = diagnosticsEnabled()
    internal var beforeCommitForTests: (() -> Unit)? = null
    internal var beforeStateWriteForTests: ((String) -> Unit)? = null

    init {
        // Repository reads stay outside the SQLite lock, and only after the gate above.
        val legacyDay = java.time.LocalDate.now().toString()
        val legacyQuotaUsed = com.liuml.apptimelimiter.data.RuleRepository(context).isParentUnlockAdRequired(legacyDay)
        transaction {
            val migrated = db.rawQuery("SELECT value FROM metadata WHERE key='legacy_import_v1'", null).use { it.moveToFirst() }
            if (!migrated) {
                listOf(ControlRuntimeStore.PREFS_NAME, "parent_auth_runtime").forEach { namespace ->
                    val editor = preferences(namespace).edit()
                    context.getSharedPreferences(namespace, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                    check(editor.commit())
                }
                val auth = preferences("parent_auth_runtime")
                if (auth.getString("quota_day", "").isNullOrBlank()) {
                    check(auth.edit().putString("quota_day", legacyDay).putInt("quota_count", if (legacyQuotaUsed) 1 else 0).commit())
                }
                db.execSQL("INSERT INTO metadata VALUES('legacy_import_v1','1')")
            }
        }
    }

    /** Nested callers join the owner's transaction. Never publish success before endTransaction. */
    @Synchronized internal fun <T> transaction(block: () -> T): T {
        if (db.inTransaction()) return block()
        db.beginTransaction()
        try {
            val result = block()
            beforeCommitForTests?.invoke()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    internal fun emit(packageName: String, incidentId: String, stage: RuntimeDiagnosticStage,
        result: RuntimeDiagnosticResult = RuntimeDiagnosticResult.ACCEPTED,
        reason: RuntimeDiagnosticReason = RuntimeDiagnosticReason.NONE) = transaction {
        if (!diagnosticsAllowed) return@transaction
        val incidentKey = com.liuml.apptimelimiter.diagnostics.DiagnosticTimelinePolicy.incidentKey(packageName, incidentId)
            ?: return@transaction
        var savepointCreated = false
        try {
            db.execSQL("SAVEPOINT diagnostic_write")
            savepointCreated = true
            // Bounded diagnostics, not authority: a local outbox fault must not deny a valid PIN.
            db.execSQL("DELETE FROM diagnostic_outbox WHERE id <= (SELECT MAX(id)-4095 FROM diagnostic_outbox)")
            db.execSQL("INSERT INTO diagnostic_outbox(event_key,package_name,incident_id,stage,result,reason,wall_ms) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any>(UUID.randomUUID().toString(), packageName, incidentKey, stage.name,
                    result.name, reason.name, System.currentTimeMillis()))
            db.execSQL("RELEASE SAVEPOINT diagnostic_write")
        } catch (error: android.database.SQLException) {
            if (savepointCreated) {
                // If rollback itself fails, propagate: the owner transaction can no longer be trusted.
                db.execSQL("ROLLBACK TO SAVEPOINT diagnostic_write")
                db.execSQL("RELEASE SAVEPOINT diagnostic_write")
            }
            android.util.Log.w("ManagerControlDatabase", "diagnostic_outbox_write_failed:${error.javaClass.simpleName}")
        }
    }

    /** Resolve repository settings BEFORE taking the SQLite monitor (never from emit). */
    fun refreshDiagnosticsSetting() { diagnosticsAllowed = diagnosticsEnabled() }

    fun pendingDiagnostics(limit: Int = 100): List<RuntimeDiagnosticEvent> {
        refreshDiagnosticsSetting()
        return transaction {
            if (!diagnosticsAllowed) {
                db.delete("diagnostic_outbox", null, null)
                return@transaction emptyList()
            }
            db.rawQuery("SELECT id,event_key,package_name,incident_id,stage,result,reason,wall_ms FROM diagnostic_outbox ORDER BY id LIMIT ?",
                arrayOf(limit.coerceIn(1, 500).toString())).use { c -> buildList {
                    while (c.moveToNext()) add(RuntimeDiagnosticEvent(c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getLong(7)))
                } }
        }
    }

    /** Call only after the timeline consumer has committed eventKey-deduplicated rows. */
    fun acknowledgeDiagnostics(ids: List<Long>) = transaction {
        ids.distinct().forEach { db.delete("diagnostic_outbox", "id=?", arrayOf(it.toString())) }
    }

    /** Clear under the consumer's drain lock before clearing its timeline, preventing replay. */
    fun discardDiagnostics() = transaction { db.delete("diagnostic_outbox", null, null); Unit }

    internal fun preferences(namespace: String): SharedPreferences = SqlPreferences(namespace)
    internal fun hasTransaction(): Boolean = db.inTransaction()
    internal fun closeForTests() = helper.close()

    /** Compatibility codec only: every read is SQLite-backed, never a SharedPreferences memory cache. */
    private inner class SqlPreferences(private val namespace: String) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = transaction {
            val values = linkedMapOf<String, Any>()
            db.rawQuery("SELECT key,value FROM state WHERE namespace=?", arrayOf(namespace)).use { c ->
                while (c.moveToNext()) {
                    val json = JSONObject(c.getString(1))
                    values[c.getString(0)] = when (json.getString("type")) {
                        "set" -> json.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                        "int" -> json.getInt("value")
                        "long" -> json.getLong("value")
                        "float" -> json.getDouble("value").toFloat()
                        "boolean" -> json.getBoolean("value")
                        else -> json.getString("value")
                    }
                }
            }
            values
        }
        override fun contains(key: String?) = all.containsKey(key)
        override fun getString(key: String?, defValue: String?): String? = all[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (all[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int) = all[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = all[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = all[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = all[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val writes = linkedMapOf<String, String?>()
            private var clearing = false
            private fun put(key: String?, type: String, value: Any?): SharedPreferences.Editor {
                requireNotNull(key)
                writes[key] = value?.let { JSONObject().put("type", type).put("value", it).toString() }
                return this
            }
            override fun putString(key: String?, value: String?) = put(key, "string", value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, "set", values?.let { JSONArray(it.toList()) })
            override fun putInt(key: String?, value: Int) = put(key, "int", value)
            override fun putLong(key: String?, value: Long) = put(key, "long", value)
            override fun putFloat(key: String?, value: Float) = put(key, "float", value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, "boolean", value)
            override fun remove(key: String?) = put(key, "string", null)
            override fun clear(): SharedPreferences.Editor { clearing = true; return this }
            override fun apply() { check(commit()) }
            override fun commit(): Boolean = transaction {
                if (clearing || writes.isNotEmpty()) beforeStateWriteForTests?.invoke(namespace)
                if (clearing) db.delete("state", "namespace=?", arrayOf(namespace))
                writes.forEach { (key, value) ->
                    if (value == null) db.delete("state", "namespace=? AND key=?", arrayOf(namespace, key))
                    else db.execSQL("INSERT OR REPLACE INTO state(namespace,key,value) VALUES(?,?,?)", arrayOf(namespace, key, value))
                }
                true
            }
        }
    }

    companion object {
        @Volatile private var instance: ManagerControlDatabase? = null
        fun get(context: Context): ManagerControlDatabase = instance ?: synchronized(this) {
            instance ?: ManagerControlDatabase(context.applicationContext).also { instance = it }
        }
    }
}
