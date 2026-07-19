package com.liuml.apptimelimiter.core

/** Prevents the limiter from terminating a core/shared system process. */
object ProcessTerminationPolicy {
    fun mayTerminate(
        targetPackage: String,
        activityPackage: String,
        uid: Int,
        firstApplicationUid: Int,
        isSystemApp: Boolean,
    ): Boolean = targetPackage.isNotBlank() &&
        targetPackage == activityPackage &&
        uid >= firstApplicationUid &&
        !isSystemApp &&
        targetPackage !in PROTECTED_PACKAGES

    private val PROTECTED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.shell",
    )
}
