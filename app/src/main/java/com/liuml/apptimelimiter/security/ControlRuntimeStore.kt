package com.liuml.apptimelimiter.security

import android.content.SharedPreferences
import com.liuml.apptimelimiter.core.ControlRuntimeMachine
import com.liuml.apptimelimiter.core.ControlRuntimeState
import com.liuml.apptimelimiter.core.ControlSessionToken
import com.liuml.apptimelimiter.core.ControlSessionTokenPolicy
import org.json.JSONObject

/** Provider-only authority. One durable owner per package; no in-memory success before commit. */
class ControlRuntimeStore(private val prefs: SharedPreferences, private val bootCount: Int,
    private val database: ManagerControlDatabase? = null,
    private val elapsedClock: (() -> Long)? = if (database != null) ({ android.os.SystemClock.elapsedRealtime() }) else null) {
    constructor(database: ManagerControlDatabase, bootCount: Int,
        elapsedClock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) :
        this(database.preferences(PREFS_NAME), bootCount, database, elapsedClock)
    data class Record(val token: ControlSessionToken, val incident: String, val state: ControlRuntimeState)

    // Production never acquires the legacy preferences monitor while holding/awaiting SQLite.
    // The complete read/modify/write operation belongs to one DB transaction, including pruning.
    private inline fun <T> authority(crossinline block: () -> T): T =
        if (database != null) database.transaction { block() } else synchronized(lock) { block() }

    fun read(token: ControlSessionToken, now: Long): Record? = authority {
        // A caller's timestamp can predate another writer while this call waits for the DB lock.
        // Sample the authority clock only after serialization; retain explicit time for SP compatibility.
        val transactionNow = elapsedClock?.invoke() ?: now
        readCurrent(token.packageName, transactionNow)?.takeIf { it.token == token }
    }

    fun claim(token: ControlSessionToken, incident: String, now: Long, replaceSession: Boolean = false): Record? = authority {
        val transactionNow = elapsedClock?.invoke() ?: now
        if (database == null && prefs in poisoned) return@authority null
        if (!ControlSessionTokenPolicy.isStructurallyValid(token) || incident.isBlank() || incident.length > 240 || bootCount < 0) return@authority null
        val old = readCurrent(token.packageName, transactionNow)
        if (old != null && old.state != ControlRuntimeState.CANCELLED) {
            // An existing event owns this session, including PIN and ad handoffs.
            if (old.token == token) return@authority if (save(old, transactionNow)) old else null
            if (old.token.ruleVersion == token.ruleVersion && old.token.groupVersion == token.groupVersion &&
                old.token.groupId == token.groupId && old.token.modeGeneration == token.modeGeneration &&
                old.token.protectionMode == token.protectionMode && !replaceSession) return@authority null
        }
        if (!prefs.contains(token.packageName) && prefs.all.size >= 128) {
            val editor = prefs.edit()
            prefs.all.keys.filter { readCurrent(it, transactionNow) == null }.forEach(editor::remove)
            if (!editor.commit()) { poisoned.add(prefs); return@authority null }
            if (prefs.all.size >= 128) return@authority null
        }
        val machine = ControlRuntimeMachine(incident, token)
        if (!machine.transition(ControlRuntimeState.LIMIT_CLAIMED, token)) return@authority null
        val record = Record(token, incident, machine.state())
        if (save(record, transactionNow)) record else null
    }

    fun transition(token: ControlSessionToken, incident: String, next: ControlRuntimeState, now: Long): Boolean = authority {
        val transactionNow = elapsedClock?.invoke() ?: now
        val old = readCurrent(token.packageName, transactionNow)?.takeIf { it.token == token } ?: return@authority false
        if (old.incident != incident) return@authority false
        val machine = ControlRuntimeMachine(incident, token, old.state)
        if (!machine.transition(next, token)) return@authority false
        if (old.state == next) return@authority true
        save(old.copy(state = machine.state()), transactionNow)
    }

    private fun readCurrent(pkg: String, now: Long): Record? = runCatching {
        if (database == null && prefs in poisoned) return null
        val json = JSONObject(prefs.getString(pkg, null) ?: return null)
        if (bootCount < 0 || json.getInt("boot") != bootCount || now < json.getLong("saved") || now >= json.getLong("expires")) return null
        val token = ControlSessionToken(pkg, json.getString("group"), json.getString("session"),
            json.getLong("rule"), json.getLong("groupVersion"),
            com.liuml.apptimelimiter.data.ProtectionMode.valueOf(json.getString("mode")),
            json.getLong("generation"), json.getLong("foregroundGeneration"))
        Record(token, json.getString("incident"), ControlRuntimeState.valueOf(json.getString("state")))
    }.getOrElse { if (database != null) throw it else null }

    private fun save(record: Record, now: Long): Boolean = runCatching {
        val t = record.token
        val json = JSONObject().put("boot", bootCount).put("saved", now).put("expires", now + LEASE_MILLIS)
            .put("group", t.groupId).put("session", t.sessionId).put("rule", t.ruleVersion)
            .put("groupVersion", t.groupVersion).put("mode", t.protectionMode.name).put("generation", t.modeGeneration)
            .put("foregroundGeneration", t.foregroundGeneration)
            .put("incident", record.incident).put("state", record.state.name)
        val previous = prefs.getString(t.packageName, null)
        if (database != null) return database.transaction {
            check(prefs.edit().putString(t.packageName, json.toString()).commit())
            if (previous == null || JSONObject(previous).optString("state") != record.state.name ||
                JSONObject(previous).optString("incident") != record.incident) {
                database.emit(t.packageName, record.incident,
                    RuntimeDiagnosticStage.valueOf(record.state.name))
            }
            true
        }
        if (prefs.edit().putString(t.packageName, json.toString()).commit()) true else {
            poisoned.add(prefs)
            // SharedPreferences changes its memory map even when disk commit returns false.
            runCatching { prefs.edit().putString(t.packageName, previous).commit() }
            false
        }
    }.getOrElse { if (database != null) throw it else { poisoned.add(prefs); false } }

    companion object {
        const val PREFS_NAME = "control_runtime_private_v1"
        // UI ownership lease is distinct from the two-minute PIN reservation deadline.
        const val LEASE_MILLIS = 600_000L
        private val lock = Any()
        private val poisoned = java.util.Collections.newSetFromMap(java.util.WeakHashMap<SharedPreferences, Boolean>())
    }
}
