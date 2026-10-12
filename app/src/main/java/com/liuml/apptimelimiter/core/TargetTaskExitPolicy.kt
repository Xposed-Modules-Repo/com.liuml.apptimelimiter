package com.liuml.apptimelimiter.core

enum class TargetWindowMode { FULLSCREEN, WINDOWED, UNKNOWN }

object TargetTaskExitPolicy {
    fun mode(windowingMode: Int, multiWindow: Boolean = false): TargetWindowMode = when {
        multiWindow || windowingMode in setOf(2, 3, 4, 5, 6) -> TargetWindowMode.WINDOWED
        windowingMode == 1 -> TargetWindowMode.FULLSCREEN
        else -> TargetWindowMode.UNKNOWN
    }

    fun matches(boundPackage: String, boundTaskId: Int, boundUser: Int,
        actualPackage: String?, actualTaskId: Int, actualUser: Int): Boolean =
        PackageNamePolicy.isValid(boundPackage) && boundTaskId >= 0 && boundUser >= 0 &&
            boundPackage == actualPackage && boundTaskId == actualTaskId && boundUser == actualUser

    fun mayGoHome(mode: TargetWindowMode): Boolean = mode == TargetWindowMode.FULLSCREEN
}
