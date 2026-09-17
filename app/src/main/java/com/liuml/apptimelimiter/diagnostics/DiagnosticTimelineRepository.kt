package com.liuml.apptimelimiter.diagnostics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Bounded, manager-private history. A failed diagnostic write never controls authorization. */
class DiagnosticTimelineRepository(context: Context, private val databaseName: String = DATABASE_NAME) : java.io.Closeable {
    private val helper = if (databaseName == DATABASE_NAME) database(context.applicationContext) else Database(context.applicationContext, databaseName)
    override fun close() { if (databaseName != DATABASE_NAME) helper.close() }

    fun record(packageName: String, incidentId: String, stage: String, result: String,
               reason: String = "", eventKey: String, wallMillis: Long = System.currentTimeMillis()): Boolean {
        val incident = DiagnosticTimelinePolicy.incidentKey(packageName, incidentId) ?: return false
        return recordHashed(packageName, incident, stage, result, reason, eventKey, wallMillis)
    }

    internal fun recordHashed(packageName: String, incident: String, stage: String, result: String,
                              reason: String, eventKey: String, wallMillis: Long): Boolean {
        if (!DiagnosticTimelinePolicy.validPackage(packageName) || !Regex("[a-f0-9]{64}").matches(incident)) return false
        if (eventKey.isBlank() || eventKey.length > 512 || wallMillis <= 0) return false
        return runCatching {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                prune(db)
                val now = System.currentTimeMillis()
                if (wallMillis >= now - DiagnosticTimelinePolicy.RETENTION_MILLIS && wallMillis <= now + 60_000) {
                    db.insertWithOnConflict("events", null, ContentValues().apply {
                        put("event_key", DiagnosticTimelinePolicy.digest(eventKey))
                        put("package", packageName)
                        put("incident", incident)
                        put("stage", DiagnosticTimelinePolicy.code(stage))
                        put("result", DiagnosticTimelinePolicy.code(result))
                        put("reason", if (reason.isBlank()) "" else DiagnosticTimelinePolicy.code(reason))
                        put("wall", wallMillis)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                    db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT ${DiagnosticTimelinePolicy.MAX_EVENTS})")
                }
                db.setTransactionSuccessful()
                true
            } finally { db.endTransaction() }
        }.getOrDefault(false)
    }

    internal fun importOutbox(events: List<com.liuml.apptimelimiter.security.RuntimeDiagnosticEvent>): Boolean = runCatching {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            prune(db)
            events.forEach { event ->
                // Invalid/expired diagnostics are discarded, not allowed to jam the durable queue.
                if (DiagnosticTimelinePolicy.validPackage(event.packageName) &&
                    Regex("[a-f0-9]{64}").matches(event.incidentId) && event.eventKey.isNotBlank() &&
                    event.wallMillis in (now - DiagnosticTimelinePolicy.RETENTION_MILLIS)..(now + 60_000)) {
                    db.insertWithOnConflict("events", null, ContentValues().apply {
                        put("event_key", DiagnosticTimelinePolicy.digest(event.eventKey))
                        put("package", event.packageName); put("incident", event.incidentId)
                        put("stage", DiagnosticTimelinePolicy.code(event.stage))
                        put("result", DiagnosticTimelinePolicy.code(event.result))
                        put("reason", DiagnosticTimelinePolicy.code(event.reason))
                        put("wall", event.wallMillis)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT ${DiagnosticTimelinePolicy.MAX_EVENTS})")
            db.setTransactionSuccessful()
            true
        } finally { db.endTransaction() }
    }.getOrDefault(false)

    fun incidents(beforeId: Long = Long.MAX_VALUE): List<DiagnosticIncident> {
        val db = helper.writableDatabase
        prune(db)
        return db.rawQuery("SELECT package, incident, MAX(id), MAX(wall), COUNT(*) FROM events GROUP BY package, incident HAVING MAX(id) < ? ORDER BY MAX(id) DESC LIMIT ?",
            arrayOf(beforeId.toString(), DiagnosticTimelinePolicy.PAGE_SIZE.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(DiagnosticIncident(cursor.getString(0), cursor.getString(1),
                cursor.getLong(2), cursor.getLong(3), cursor.getInt(4))) }
        }
    }

    fun events(incident: String, afterId: Long = 0L): List<DiagnosticTimelineEvent> {
        val db = helper.writableDatabase
        prune(db)
        val afterWall = if (afterId <= 0L) Long.MIN_VALUE else db.rawQuery(
            "SELECT wall FROM events WHERE id = ? AND incident = ?", arrayOf(afterId.toString(), incident)
        ).use { if (it.moveToFirst()) it.getLong(0) else Long.MIN_VALUE }
        return db.rawQuery("SELECT id, package, incident, stage, result, reason, wall FROM events WHERE incident = ? AND (wall > ? OR (wall = ? AND id > ?)) ORDER BY wall, id LIMIT ?",
            arrayOf(incident, afterWall.toString(), afterWall.toString(), afterId.toString(), DiagnosticTimelinePolicy.PAGE_SIZE.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(DiagnosticTimelineEvent(cursor.getLong(0), cursor.getString(1),
                cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getLong(6))) }
        }
    }

    fun exportText(): String {
        val db = helper.writableDatabase
        prune(db)
        return db.rawQuery("SELECT package, incident, stage, result, reason, wall FROM events ORDER BY wall, id", null).use { cursor ->
            buildString {
                appendLine("\n# Structured restriction events (incident identifiers are hashed)")
                while (cursor.moveToNext()) appendLine("${cursor.getLong(5)}\t${cursor.getString(0)}\t${cursor.getString(1).take(12)}\t${cursor.getString(2)}\t${cursor.getString(3)}\t${cursor.getString(4)}")
            }
        }
    }

    fun clear() { helper.writableDatabase.delete("events", null, null) }
    private fun prune(db: SQLiteDatabase) {
        db.delete("events", "wall < ?", arrayOf((System.currentTimeMillis() - DiagnosticTimelinePolicy.RETENTION_MILLIS).toString()))
    }

    private class Database(context: Context, name: String = DATABASE_NAME) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, event_key TEXT NOT NULL UNIQUE, package TEXT NOT NULL, incident TEXT NOT NULL, stage TEXT NOT NULL, result TEXT NOT NULL, reason TEXT NOT NULL, wall INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX events_incident ON events(incident, id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    companion object {
        private const val DATABASE_NAME = "diagnostic_timeline_private_v1.db"
        @Volatile private var instance: Database? = null
        private fun database(context: Context): Database = instance ?: synchronized(this) {
            instance ?: Database(context).also { instance = it }
        }
    }
}
