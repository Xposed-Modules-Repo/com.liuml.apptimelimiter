package com.liuml.apptimelimiter.core

/** Keeps the one-device ADX debugger opt-in out of release builds. */
object TopOnDebuggerPolicy {
    const val ADX_NETWORK_FIRM_ID = 66

    fun shouldRequestDeviceId(isDebug: Boolean, enabled: Boolean, deviceId: String): Boolean =
        isDebug && enabled && deviceId.isBlank()

    fun shouldApplyDebugger(isDebug: Boolean, enabled: Boolean, deviceId: String): Boolean =
        isDebug && enabled && deviceId.isNotBlank()
}
