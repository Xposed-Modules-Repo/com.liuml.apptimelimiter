package com.liuml.apptimelimiter.core

object WarningBannerLayoutPolicy {
    const val INLINE_MIN_WIDTH_DP = 480

    fun shouldStackActions(
        fullScreen: Boolean,
        availableWidthDp: Int,
        actionCount: Int,
        fontScale: Float,
    ): Boolean = !fullScreen && (
        actionCount >= 2 ||
            availableWidthDp < INLINE_MIN_WIDTH_DP ||
            fontScale > 1.15f
        )
}
