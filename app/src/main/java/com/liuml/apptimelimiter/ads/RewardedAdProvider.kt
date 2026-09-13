package com.liuml.apptimelimiter.ads

import android.app.Activity

enum class RewardedAdLoadStatus {
    NOT_STARTED,
    LOADING,
    READY,
    FAILED,
}

data class RewardedAdLoadState(
    val status: RewardedAdLoadStatus,
    val failureReason: String = "",
)

interface RewardedAdProvider {
    fun preload()
    fun isReady(): Boolean
    fun loadState(): RewardedAdLoadState
    fun show(
        activity: Activity,
        onReward: () -> Unit,
        onClosed: () -> Unit,
        onFailed: (reason: String) -> Unit,
    )
    fun destroy()
    fun isPrivacyConsentRequired(): Boolean
}
