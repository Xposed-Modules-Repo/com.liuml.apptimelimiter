package com.liuml.apptimelimiter.ads

import android.content.Context
import android.util.Log
import com.thinkup.core.api.TUSDK
import com.thinkup.core.api.TUDebuggerConfig
import com.thinkup.core.basead.adx.api.TUAdxSetting
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.core.TopOnDebuggerPolicy

/** Initializes the SDK once in Time Stop's own process, before any ad is requested. */
object TopOnSdkBootstrap {
    private val lock = Any()
    @Volatile private var initialized = false
    @Volatile private var failureReason = ""

    fun initialize(
        context: Context,
        appId: String,
        appKey: String,
        placementId: String,
        enabled: Boolean,
    ): String? = synchronized(lock) {
        if (!enabled) return@synchronized "topon_disabled"
        if (appId.isBlank() || appKey.isBlank() || placementId.isBlank()) {
            return@synchronized "topon_local_config_missing"
        }
        if (initialized) return@synchronized null
        if (failureReason.isNotBlank()) return@synchronized failureReason
        runCatching {
            // ADX is the configured client-bidding source and must be selected before init.
            TUAdxSetting.getInstance().openAdxNetworkMode(placementId)
            if (BuildConfig.DEBUG) {
                TUSDK.setNetworkLogDebug(true)
                TUSDK.integrationChecking(context.applicationContext)
            }
            configureAdxDebuggerBeforeInit(context.applicationContext)
            TUSDK.init(context.applicationContext, appId, appKey)
            requestTestDeviceIdAfterInit(context.applicationContext)
            initialized = true
        }.onFailure { error ->
            failureReason = "topon_initialization_${error.javaClass.simpleName.take(40)}"
        }
        failureReason.ifBlank { null }
    }

    private fun configureAdxDebuggerBeforeInit(context: Context) {
        val deviceId = BuildConfig.TOPON_ADX_DEBUGGER_DEVICE_ID.trim()
        if (TopOnDebuggerPolicy.shouldApplyDebugger(
                isDebug = BuildConfig.DEBUG,
                enabled = BuildConfig.TOPON_ADX_DEBUGGER_ENABLED,
                deviceId = deviceId,
            )
        ) {
            TUSDK.setDebuggerConfig(
                context,
                deviceId,
                TUDebuggerConfig.Builder(TopOnDebuggerPolicy.ADX_NETWORK_FIRM_ID)
                    .setNetworkFirmId(TopOnDebuggerPolicy.ADX_NETWORK_FIRM_ID)
                    .build(),
            )
        }
    }

    private fun requestTestDeviceIdAfterInit(context: Context) {
        val deviceId = BuildConfig.TOPON_ADX_DEBUGGER_DEVICE_ID.trim()
        if (TopOnDebuggerPolicy.shouldRequestDeviceId(
                isDebug = BuildConfig.DEBUG,
                enabled = BuildConfig.TOPON_ADX_DEBUGGER_ENABLED,
                deviceId = deviceId,
            )
        ) {
            // The id is emitted only to this device's transient Logcat. It is never persisted
            // in app data, diagnostics, source, or a release APK.
            TUSDK.testModeDeviceInfo(context) { generatedId ->
                if (generatedId.isNotBlank()) {
                    Log.i(TAG, "TOPON_TEST_DEVICE_ID=$generatedId")
                }
            }
        }
    }

    private const val TAG = "TimeStopTopOn"
}
