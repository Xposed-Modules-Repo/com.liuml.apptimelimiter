package com.liuml.apptimelimiter.nonroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuTargetPolicyTest {
    @Test
    fun `only configured third party targets are allowed`() {
        assertTrue(
            ShizukuTargetPolicy.isAllowed(
                packageName = "com.example.video",
                ownPackageName = "com.liuml.apptimelimiter",
                configuredPackages = setOf("com.example.video"),
                systemOrUpdatedSystemApp = false,
                launcherPackage = "com.example.launcher",
                protectedPackages = setOf("android", "com.android.systemui"),
            ),
        )
        assertFalse(
            ShizukuTargetPolicy.isAllowed(
                packageName = "com.example.video",
                ownPackageName = "com.liuml.apptimelimiter",
                configuredPackages = emptySet(),
                systemOrUpdatedSystemApp = false,
                launcherPackage = null,
                protectedPackages = emptySet(),
            ),
        )
    }

    @Test
    fun `system own launcher and protected packages are rejected`() {
        val base = setOf(
            "com.liuml.apptimelimiter",
            "com.example.launcher",
            "com.android.systemui",
            "com.example.system",
        )
        assertFalse(
            ShizukuTargetPolicy.isAllowed(
                "com.liuml.apptimelimiter",
                "com.liuml.apptimelimiter",
                base,
                false,
                "com.example.launcher",
                setOf("com.android.systemui"),
            ),
        )
        assertFalse(
            ShizukuTargetPolicy.isAllowed(
                "com.example.launcher",
                "com.liuml.apptimelimiter",
                base,
                false,
                "com.example.launcher",
                setOf("com.android.systemui"),
            ),
        )
        assertFalse(
            ShizukuTargetPolicy.isAllowed(
                "com.android.systemui",
                "com.liuml.apptimelimiter",
                base,
                false,
                "com.example.launcher",
                setOf("com.android.systemui"),
            ),
        )
        assertFalse(
            ShizukuTargetPolicy.isAllowed(
                "com.example.system",
                "com.liuml.apptimelimiter",
                base,
                true,
                "com.example.launcher",
                setOf("com.android.systemui"),
            ),
        )
    }
}
