package com.liuml.apptimelimiter.core

/** Presentation decisions shared by the standalone restriction page and its callers. */
object RestrictionPagePresentationPolicy {
    fun showTemporaryAccess(allowPin: Boolean, allowAd: Boolean): Boolean = allowPin || allowAd

    fun canRequestRewardedAd(
        isVisibleForRestriction: Boolean,
        extensionEnabled: Boolean,
        requestInFlight: Boolean,
        eligibilityFailure: String?,
    ): Boolean = isVisibleForRestriction &&
        extensionEnabled &&
        !requestInFlight &&
        eligibilityFailure == null

    fun isPersistentRewardedAdEligibilityFailure(reason: String): Boolean = reason in setOf(
        "extension_session_limit_reached",
        "extension_daily_limit_reached",
        "rewarded_ad_quota_reached",
        "stale_ad_request",
        "quota_or_stale",
    )

    fun shouldRequestAdImmediately(privacyConsentGranted: Boolean): Boolean = privacyConsentGranted

    fun mayClaimPage(
        activeIncidentId: String?,
        activeExpiresAtMillis: Long,
        requestedIncidentId: String,
        nowMillis: Long,
    ): Boolean = requestedIncidentId.isNotBlank() && (
        activeIncidentId.isNullOrBlank() ||
            activeExpiresAtMillis <= nowMillis ||
            activeIncidentId == requestedIncidentId
        )
}
