package com.liuml.apptimelimiter.core

enum class RuleStorageBootstrapAction {
    KEEP,
    ADOPT_EXISTING,
    RESET_AFTER_DATA_CLEAR,
}

object RuleStorageBootstrapPolicy {
    fun action(
        privateMarkerPresent: Boolean,
        sharedMarkerPresent: Boolean,
        primaryMarkerPresent: Boolean,
    ): RuleStorageBootstrapAction = when {
        primaryMarkerPresent -> RuleStorageBootstrapAction.KEEP
        !privateMarkerPresent && sharedMarkerPresent ->
            RuleStorageBootstrapAction.RESET_AFTER_DATA_CLEAR
        else -> RuleStorageBootstrapAction.ADOPT_EXISTING
    }
}
