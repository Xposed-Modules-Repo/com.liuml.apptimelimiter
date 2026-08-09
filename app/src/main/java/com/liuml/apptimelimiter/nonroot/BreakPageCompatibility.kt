package com.liuml.apptimelimiter.nonroot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

enum class BreakPageCompatibilityStage {
    START_EXCEPTION,
    CONFIRMATION_TIMEOUT,
}

data class BreakPageCompatibilitySnapshot(
    val lastRequestedAtMillis: Long = 0L,
    val lastConfirmedAtMillis: Long = 0L,
    val lastFailureAtMillis: Long = 0L,
    val lastFailureStage: BreakPageCompatibilityStage? = null,
    val lastFailurePackageName: String = "",
    val lastFailureDetail: String = "",
    val lastFailureManufacturer: String = "",
)

class BreakPageCompatibilityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun snapshot(): BreakPageCompatibilitySnapshot = BreakPageCompatibilitySnapshot(
        lastRequestedAtMillis = prefs.getLong(KEY_REQUESTED_AT, 0L),
        lastConfirmedAtMillis = prefs.getLong(KEY_CONFIRMED_AT, 0L),
        lastFailureAtMillis = prefs.getLong(KEY_FAILURE_AT, 0L),
        lastFailureStage = prefs.getString(KEY_FAILURE_STAGE, null)?.let {
            runCatching { BreakPageCompatibilityStage.valueOf(it) }.getOrNull()
        },
        lastFailurePackageName = prefs.getString(KEY_FAILURE_PACKAGE, null).orEmpty(),
        lastFailureDetail = prefs.getString(KEY_FAILURE_DETAIL, null).orEmpty(),
        lastFailureManufacturer = prefs.getString(KEY_FAILURE_MANUFACTURER, null).orEmpty(),
    )

    fun recordRequested(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_REQUESTED_AT, nowMillis).commit()
    }

    fun recordConfirmed(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_CONFIRMED_AT, nowMillis).commit()
    }

    fun recordFailure(
        packageName: String,
        stage: BreakPageCompatibilityStage,
        detail: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        prefs.edit()
            .putLong(KEY_FAILURE_AT, nowMillis)
            .putString(KEY_FAILURE_STAGE, stage.name)
            .putString(KEY_FAILURE_PACKAGE, packageName.take(256))
            .putString(KEY_FAILURE_DETAIL, detail.take(300))
            .putString(KEY_FAILURE_MANUFACTURER, Build.MANUFACTURER.take(80))
            .commit()
    }

    fun shouldShowGuidance(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val previous = prefs.getLong(KEY_GUIDANCE_AT, 0L)
        return previous <= 0L || nowMillis < previous || nowMillis - previous >= GUIDANCE_WINDOW_MILLIS
    }

    fun markGuidanceShown(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_GUIDANCE_AT, nowMillis).commit()
    }

    private companion object {
        const val PREFS_NAME = "break_page_compatibility"
        const val KEY_REQUESTED_AT = "requested_at"
        const val KEY_CONFIRMED_AT = "confirmed_at"
        const val KEY_FAILURE_AT = "failure_at"
        const val KEY_FAILURE_STAGE = "failure_stage"
        const val KEY_FAILURE_PACKAGE = "failure_package"
        const val KEY_FAILURE_DETAIL = "failure_detail"
        const val KEY_FAILURE_MANUFACTURER = "failure_manufacturer"
        const val KEY_GUIDANCE_AT = "guidance_at"
        const val GUIDANCE_WINDOW_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

enum class OemCompatibilityDestination {
    OEM_SETTINGS,
    APP_DETAILS,
    FAILED,
}

data class OemCompatibilityOpenResult(
    val destination: OemCompatibilityDestination,
    val component: String = "",
    val error: String = "",
)

object OemCompatibilityNavigator {
    fun open(context: Context): OemCompatibilityOpenResult {
        val appContext = context.applicationContext
        val candidates = candidates(Build.MANUFACTURER, appContext.packageName)
        for (intent in candidates) {
            if (intent.resolveActivity(appContext.packageManager) == null) continue
            val result = runCatching {
                appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (result.isSuccess) {
                return OemCompatibilityOpenResult(
                    destination = OemCompatibilityDestination.OEM_SETTINGS,
                    component = intent.component?.flattenToShortString().orEmpty(),
                )
            }
        }
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            appContext.startActivity(fallback)
            OemCompatibilityOpenResult(OemCompatibilityDestination.APP_DETAILS)
        }.getOrElse {
            OemCompatibilityOpenResult(
                destination = OemCompatibilityDestination.FAILED,
                error = "${it.javaClass.simpleName}: ${it.message.orEmpty()}".take(300),
            )
        }
    }

    internal fun candidates(manufacturer: String, packageName: String): List<Intent> {
        val brand = manufacturer.lowercase()
        fun explicit(pkg: String, cls: String) = Intent().apply {
            component = ComponentName(pkg, cls)
            putExtra("extra_pkgname", packageName)
            putExtra("package_name", packageName)
        }
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                listOf(
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setPackage("com.miui.securitycenter")
                        .putExtra("extra_pkgname", packageName),
                    explicit(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    ),
                    explicit(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                    ),
                )
            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ->
                listOf(
                    explicit(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.permission.startup.StartupAppListActivity",
                    ),
                    explicit(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    ),
                )
            brand.contains("vivo") || brand.contains("iqoo") -> listOf(
                explicit(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
            )
            brand.contains("huawei") || brand.contains("honor") -> listOf(
                explicit(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            )
            else -> emptyList()
        }
    }

    internal fun vendorFamily(manufacturer: String): String {
        val brand = manufacturer.lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "XIAOMI"
            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ->
                "OPLUS"
            brand.contains("vivo") || brand.contains("iqoo") -> "VIVO"
            brand.contains("huawei") || brand.contains("honor") -> "HUAWEI"
            else -> "OTHER"
        }
    }
}
