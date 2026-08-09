package com.liuml.apptimelimiter.nonroot

object ShizukuTargetPolicy {
    fun isAllowed(
        packageName: String,
        ownPackageName: String,
        configuredPackages: Set<String>,
        systemOrUpdatedSystemApp: Boolean,
        launcherPackage: String?,
        protectedPackages: Set<String>,
    ): Boolean = packageName.isNotBlank() &&
        packageName != ownPackageName &&
        packageName in configuredPackages &&
        !systemOrUpdatedSystemApp &&
        packageName != launcherPackage &&
        packageName !in protectedPackages
}
