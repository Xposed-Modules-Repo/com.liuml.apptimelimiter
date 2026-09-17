package com.liuml.apptimelimiter.core

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.util.Collections
import java.util.IdentityHashMap
import java.io.File

/** Android boundary; policy calculations remain pure Kotlin. */
object CooldownClock {
    fun bootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1).takeIf { it >= 0 } ?: -1

    private val failed = Collections.newSetFromMap(IdentityHashMap<SharedPreferences, Boolean>())

    /** These stores are owned by the app's default process; remote clients must use Provider. */
    @Synchronized fun requireOwner(context: Context, prefs: SharedPreferences) {
        val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            File("/proc/self/cmdline").inputStream().use { stream ->
                val bytes = ByteArray(256)
                val count = stream.read(bytes)
                check(count > 0) { "Cannot identify cooldown owner process" }
                String(bytes, 0, count, Charsets.UTF_8).substringBefore('\u0000')
            }
        }
        check(processName == context.applicationInfo.processName) {
            "Cooldown access requires owner process / Provider"
        }
        requireHealthy(prefs)
    }

    @Synchronized fun requireHealthy(prefs: SharedPreferences) {
        check(prefs !in failed) { "Cooldown storage failed; restart required" }
    }

    /** commit(false) may already have changed SharedPreferences' in-memory map. */
    @Synchronized fun commit(prefs: SharedPreferences, editor: SharedPreferences.Editor) {
        check(prefs !in failed) { "Cooldown storage failed; restart required" }
        try {
            check(editor.commit()) { "Failed to persist cooldown" }
        } catch (failure: Exception) {
            failed.add(prefs)
            throw failure
        }
    }
}
