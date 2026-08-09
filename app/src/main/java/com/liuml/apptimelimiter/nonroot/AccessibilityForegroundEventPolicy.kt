package com.liuml.apptimelimiter.nonroot

import com.liuml.apptimelimiter.data.NonRootCompatibilityMode

/**
 * TYPE_WINDOWS_CHANGED is also emitted when Time Stop adds or removes its own accessibility
 * overlay. Self-package events of that type must be ignored, while target-package events are
 * needed on ROMs that do not emit TYPE_WINDOW_STATE_CHANGED when an existing task resumes.
 */
object AccessibilityForegroundEventPolicy {
    fun packageForForegroundEvent(
        eventType: Int,
        packageName: CharSequence?,
        ownPackageName: String,
        ignoreOwnPackageWindowEvent: Boolean = false,
        compatibilityMode: NonRootCompatibilityMode = NonRootCompatibilityMode.STANDARD,
    ): String? {
        val normalized = packageName?.toString()?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        if (normalized == ownPackageName && ignoreOwnPackageWindowEvent) {
            return null
        }
        return when (
            ForegroundSignalPolicy.sourceForEvent(
                eventType = eventType,
                mode = compatibilityMode,
            )
        ) {
            ForegroundSignalSource.ACCESSIBILITY_WINDOW_STATE -> normalized
            ForegroundSignalSource.ACCESSIBILITY_WINDOWS_CHANGED ->
                normalized.takeUnless { it == ownPackageName }
            ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT ->
                normalized.takeUnless { it == ownPackageName }
            else -> null
        }
    }
}
