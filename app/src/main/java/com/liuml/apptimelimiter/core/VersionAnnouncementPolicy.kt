package com.liuml.apptimelimiter.core

object VersionAnnouncementPolicy {
    fun shouldShow(currentVersionCode: Int, lastAcknowledgedVersionCode: Int): Boolean =
        currentVersionCode > 0 && currentVersionCode != lastAcknowledgedVersionCode
}
