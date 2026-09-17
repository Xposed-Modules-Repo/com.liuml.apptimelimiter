package com.liuml.apptimelimiter.security

import android.content.SharedPreferences
import org.json.JSONObject

/** Authenticated transport ownership, not a claim that Android independently attests window focus.
 * Hook emits this capability only from its resumed/focused Activity path after PIN handoff.
 * The manager's accessibility adapter supplies in-process evidence instead.
 */
class ControlResumeEvidence(private val prefs: SharedPreferences, private val boot: Int) {
    private var healthy = true
    @Synchronized fun register(session: String, pkg: String, uid: Int, pid: Int, now: Long): String? {
        if (!healthy || session.isBlank() || session.length > 160 || boot < 0 || pid <= 0) return null
        val existing = read(pkg)
        if (existing != null && existing.optString("session") == session && existing.optInt("uid") == uid &&
            existing.optInt("pid") == pid && existing.optInt("boot") == boot) return existing.optString("capability")
        if (!prefs.contains(pkg) && prefs.all.size >= 128) return null
        val capability = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()
        val value = JSONObject().put("session", session).put("uid", uid).put("pid", pid).put("boot", boot)
            .put("capability", capability).put("created", now)
        val previous = prefs.getString(pkg, null)
        return if (prefs.edit().putString(pkg, value.toString()).commit()) capability else {
            healthy = false
            runCatching { prefs.edit().putString(pkg, previous).commit() }
            null
        }
    }

    @Synchronized fun matches(session: String, pkg: String, uid: Int, pid: Int, capability: String): Boolean {
        val r = read(pkg) ?: return false
        return healthy && capability.isNotBlank() && r.optInt("boot") == boot && r.optString("session") == session &&
            r.optInt("uid") == uid && r.optInt("pid") == pid && r.optString("capability") == capability
    }

    private fun read(pkg: String): JSONObject? = runCatching { JSONObject(prefs.getString(pkg, null) ?: return null) }.getOrNull()

    companion object {
        @Volatile private var accessibilityEvidence: Triple<String, String, Long>? = null
        fun reportAccessibilityForeground(pkg: String, session: String, now: Long) {
            accessibilityEvidence = Triple(pkg, session, now)
        }
        fun accessibilityMatches(pkg: String, session: String, now: Long): Boolean = accessibilityEvidence?.let {
            it.first == pkg && it.second == session && now - it.third in 0L..1_000L
        } == true
    }
}
