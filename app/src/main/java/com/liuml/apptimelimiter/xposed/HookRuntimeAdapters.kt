package com.liuml.apptimelimiter.xposed

import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences

internal interface RulePreferences {
    fun reload()

    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun getLong(key: String, defaultValue: Long): Long

    fun getString(key: String, defaultValue: String?): String?

    fun getStringSet(key: String, defaultValue: Set<String>): Set<String>?
}

internal class LegacyRulePreferences(
    private val preferences: XSharedPreferences,
) : RulePreferences {
    init {
        preferences.makeWorldReadable()
        runCatching(preferences::reload)
    }

    override fun reload() {
        preferences.reload()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        preferences.getBoolean(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Long =
        preferences.getLong(key, defaultValue)

    override fun getString(key: String, defaultValue: String?): String? =
        preferences.getString(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String>? =
        preferences.getStringSet(key, defaultValue)
}

internal class ModernRulePreferences(
    private val preferences: SharedPreferences,
) : RulePreferences {
    override fun reload() = Unit

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        preferences.getBoolean(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Long =
        preferences.getLong(key, defaultValue)

    override fun getString(key: String, defaultValue: String?): String? =
        preferences.getString(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String>? =
        preferences.getStringSet(key, defaultValue)
}

internal object EmptyRulePreferences : RulePreferences {
    override fun reload() = Unit

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue

    override fun getLong(key: String, defaultValue: Long): Long = defaultValue

    override fun getString(key: String, defaultValue: String?): String? = defaultValue

    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> = defaultValue
}

internal fun interface HookLogger {
    fun log(message: String, error: Throwable?)
}

internal fun HookLogger.log(message: String) = log(message, null)

internal fun interface ConstructorHookInstaller {
    fun hook(targetClass: Class<*>, onCreated: (Any?) -> Unit): Boolean
}

internal object ProcessHookInstallationRegistry {
    private var claimed = false

    @Synchronized
    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}
