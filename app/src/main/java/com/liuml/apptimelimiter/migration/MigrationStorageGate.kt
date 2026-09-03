package com.liuml.apptimelimiter.migration

import android.content.Context
import com.liuml.apptimelimiter.BuildConfig

/** Prevents Modern remote preferences from being populated before migration is complete. */
internal object MigrationStorageGate {
    fun maySynchronizeMirror(context: Context): Boolean {
        if (!BuildConfig.MODERN_XPOSED_ENABLED) return true
        val state = context.applicationContext.getSharedPreferences(
            STATE_PREFS,
            Context.MODE_PRIVATE,
        )
        return state.getBoolean(KEY_IMPORTED, false) ||
            state.getBoolean(KEY_DIRECT_LEGACY_IMPORTED, false) ||
            state.getBoolean(KEY_FRESH_INSTALL, false)
    }

    const val STATE_PREFS = "legacy_modern_migration"
    const val KEY_IMPORTED = "imported"
    const val KEY_DIRECT_LEGACY_IMPORTED = "direct_legacy_imported"
    const val KEY_FRESH_INSTALL = "fresh_install"
}
