package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTerminationPolicyTest {
    @Test
    fun `ordinary target app process can be terminated`() {
        assertTrue(
            ProcessTerminationPolicy.mayTerminate(
                targetPackage = "com.example.video",
                activityPackage = "com.example.video",
                uid = 10_123,
                firstApplicationUid = 10_000,
                isSystemApp = false,
            ),
        )
    }

    @Test
    fun `core uid and mismatched activity are protected`() {
        assertFalse(
            ProcessTerminationPolicy.mayTerminate("android", "android", 1_000, 10_000, true),
        )
        assertFalse(
            ProcessTerminationPolicy.mayTerminate(
                "com.example.video",
                "com.example.other",
                10_123,
                10_000,
                false,
            ),
        )
    }

    @Test
    fun `known system process stays protected even with application uid`() {
        assertFalse(
            ProcessTerminationPolicy.mayTerminate(
                "com.android.systemui",
                "com.android.systemui",
                10_123,
                10_000,
                true,
            ),
        )
    }

    @Test
    fun `any system app is protected from process termination`() {
        assertFalse(
            ProcessTerminationPolicy.mayTerminate(
                "com.vendor.camera",
                "com.vendor.camera",
                10_123,
                10_000,
                true,
            ),
        )
    }
}
