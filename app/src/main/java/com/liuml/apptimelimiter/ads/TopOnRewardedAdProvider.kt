package com.liuml.apptimelimiter.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.liuml.apptimelimiter.core.RewardedAdLoadPolicy
import com.thinkup.core.api.AdError
import com.thinkup.core.api.TUAdInfo
import com.thinkup.rewardvideo.api.TURewardVideoAd
import com.thinkup.rewardvideo.api.TURewardVideoListener
import java.util.concurrent.atomic.AtomicBoolean

/** TopOn is initialized and used only by Time Stop's own activities. */
class TopOnRewardedAdProvider(
    context: Context,
    private val appId: String,
    private val appKey: String,
    private val placementId: String,
    private val testMode: Boolean,
    private val privacyConsentGranted: Boolean = false,
) : RewardedAdProvider {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var listenerGeneration = 0L
    private var destroyed = false
    private val showing = AtomicBoolean(false)
    private val isConfigured = appId.isNotBlank() && appKey.isNotBlank() && placementId.isNotBlank()
    private var ad: TURewardVideoAd? = null
    private var rewardCallback: (() -> Unit)? = null
    private var closeCallback: (() -> Unit)? = null
    private var failCallback: ((String) -> Unit)? = null
    private var rewardSent = false
    private val loadInFlight = AtomicBoolean(false)
    @Volatile private var loadFailureReason = ""
    @Volatile private var initializationFailureReason = ""
    @Volatile private var lastLoadFailureAtElapsedMillis = Long.MIN_VALUE

    init {
        if (privacyConsentGranted && isConfigured) {
            initializationFailureReason = TopOnSdkBootstrap.initialize(
                context = context.applicationContext,
                appId = appId,
                appKey = appKey,
                placementId = placementId,
                enabled = !testMode,
            ).orEmpty()
            if (initializationFailureReason.isBlank()) {
                createAd()
            }
        }
    }

    override fun preload() {
        if (destroyed) return
        if (ad == null && privacyConsentGranted && isConfigured && initializationFailureReason.isBlank()) createAd()
        if (testMode || isReady() || !loadInFlight.compareAndSet(false, true)) return
        if (!RewardedAdLoadPolicy.isRetryAllowed(
                lastLoadFailureAtElapsedMillis,
                SystemClock.elapsedRealtime(),
            )
        ) {
            loadFailureReason = "ad_retry_cooldown"
            loadInFlight.set(false)
            return
        }
        val video = ad
        if (video == null) {
            loadFailureReason = initializationFailureReason.ifBlank { "topon_initialization_failed" }
            loadInFlight.set(false)
            return
        }
        loadFailureReason = ""
        runCatching { video.load() }
            .onFailure {
                loadFailureReason = "ad_load_call_failed"
                loadInFlight.set(false)
            }
    }

    override fun isReady(): Boolean = testMode || (
        isConfigured && runCatching { ad?.isAdReady() == true }.getOrDefault(false)
    )

    override fun loadState(): RewardedAdLoadState = when {
        testMode || isReady() -> RewardedAdLoadState(RewardedAdLoadStatus.READY)
        !isConfigured -> RewardedAdLoadState(RewardedAdLoadStatus.FAILED, "topon_local_config_missing")
        loadFailureReason.isNotBlank() -> RewardedAdLoadState(
            RewardedAdLoadStatus.FAILED,
            loadFailureReason,
        )
        loadInFlight.get() -> RewardedAdLoadState(RewardedAdLoadStatus.LOADING)
        else -> RewardedAdLoadState(RewardedAdLoadStatus.NOT_STARTED)
    }

    override fun show(
        activity: Activity,
        onReward: () -> Unit,
        onClosed: () -> Unit,
        onFailed: (reason: String) -> Unit,
    ) {
        if (!showing.compareAndSet(false, true)) {
            onFailed("ad_already_showing")
            return
        }
        rewardCallback = onReward
        closeCallback = onClosed
        failCallback = onFailed
        rewardSent = false
        if (testMode) {
            // Debug-only deterministic test path; it is never enabled in release builds.
            mainHandler.postDelayed({
                if (showing.get()) {
                    rewardSent = true
                    onReward()
                    showing.set(false)
                    onClosed()
                }
            }, TEST_REWARD_DELAY_MS)
            return
        }
        if (!isConfigured) {
            finishFailure("topon_local_config_missing")
            return
        }
        val video = ad
        if (video == null || !isReady()) {
            finishFailure("ad_not_ready")
            return
        }
        runCatching { video.show(activity) }
            .onFailure { finishFailure(it.javaClass.simpleName) }
    }

    override fun destroy() {
        destroyed = true
        listenerGeneration++
        loadInFlight.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        showing.set(false)
        rewardCallback = null
        closeCallback = null
        failCallback = null
        ad = null
    }

    override fun isPrivacyConsentRequired(): Boolean = !privacyConsentGranted

    private fun createAd() {
        val generation = ++listenerGeneration
        ad = TURewardVideoAd(appContext, placementId).also { it.setAdListener(newListener(generation)) }
    }

    private fun dispatch(generation: Long, action: () -> Unit) {
        mainHandler.post { if (!destroyed && generation == listenerGeneration) action() }
    }

    private fun clearCallbacks() {
        rewardCallback = null
        closeCallback = null
        failCallback = null
    }

    private fun newListener(generation: Long) = object : TURewardVideoListener {
        override fun onRewardedVideoAdLoaded() = dispatch(generation) {
            loadInFlight.set(false)
            loadFailureReason = ""
            lastLoadFailureAtElapsedMillis = Long.MIN_VALUE
        }
        override fun onRewardedVideoAdFailed(error: AdError) = dispatch(generation) {
            loadInFlight.set(false)
            lastLoadFailureAtElapsedMillis = SystemClock.elapsedRealtime()
            loadFailureReason = "load_failed:${safeErrorSignature(error)}"
            if (showing.get()) finishFailure(loadFailureReason)
            else {
                listenerGeneration++
                ad = null
            }
        }
        override fun onRewardedVideoAdPlayStart(adInfo: TUAdInfo) = Unit
        override fun onRewardedVideoAdPlayEnd(adInfo: TUAdInfo) = Unit
        override fun onRewardedVideoAdPlayFailed(error: AdError, adInfo: TUAdInfo) = dispatch(generation) {
            finishFailure("play_failed:${safeErrorSignature(error)}")
        }
        override fun onReward(adInfo: TUAdInfo) = dispatch(generation) {
            if (showing.get() && !rewardSent) {
                rewardSent = true
                rewardCallback?.invoke()
            }
        }
        override fun onRewardedVideoAdClosed(adInfo: TUAdInfo) = dispatch(generation) {
            if (showing.getAndSet(false)) {
                val callback = closeCallback
                clearCallbacks()
                listenerGeneration++
                ad = null
                callback?.invoke()
            }
            preload()
        }
        override fun onRewardedVideoAdPlayClicked(adInfo: TUAdInfo) = Unit
    }

    private fun finishFailure(reason: String) {
        if (showing.getAndSet(false)) {
            val callback = failCallback
            clearCallbacks()
            listenerGeneration++
            ad = null
            loadInFlight.set(false)
            callback?.invoke(reason)
        }
    }

    private fun safeErrorSignature(error: AdError): String {
        return com.liuml.apptimelimiter.core.AdFailureDiagnosticPolicy.signature(
            runCatching { error.code }.getOrNull(),
            runCatching { error.platformCode }.getOrNull(),
            runCatching { error.fullErrorInfo }.getOrNull(),
        )
    }

    private companion object {
        const val TEST_REWARD_DELAY_MS = 1_000L
    }
}
