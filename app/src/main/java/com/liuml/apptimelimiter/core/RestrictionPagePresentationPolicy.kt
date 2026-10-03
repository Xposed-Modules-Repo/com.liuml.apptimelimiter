package com.liuml.apptimelimiter.core

/** Presentation decisions shared by the standalone restriction page and its callers. */
object RestrictionPagePresentationPolicy {
    fun showTemporaryAccess(allowPin: Boolean, allowExtension: Boolean): Boolean = allowPin || allowExtension

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
