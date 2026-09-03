package com.liuml.apptimelimiter.backup

import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableBackupCodecTest {
    @Test
    fun roundTripPreservesPortableConfiguration() {
        val source = sampleBackup()

        val encoded = PortableBackupCodec.encode(source)
        val decoded = PortableBackupCodec.decode(encoded)

        assertEquals(PortableBackupPolicy.normalize(source), decoded)
        assertFalse(encoded.contains("verifier", ignoreCase = true))
        assertFalse(encoded.contains("salt", ignoreCase = true))
        assertFalse(encoded.contains("heartbeat", ignoreCase = true))
        assertFalse(encoded.contains("protectionMode", ignoreCase = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun modifiedBodyFailsIntegrityCheck() {
        val encoded = PortableBackupCodec.encode(sampleBackup())
            .replace("0.11.13", "0.11.99")

        PortableBackupCodec.decode(encoded)
    }

    @Test
    fun rejectsAppWithPersonalRuleInsideGroup() {
        val backup = sampleBackup().copy(
            rules = listOf(AppRule("com.example.reader", sessionPlanningEnabled = true)),
            groups = listOf(
                AppGroup(
                    id = "reading",
                    name = "Reading",
                    packageNames = setOf("com.example.reader"),
                ),
            ),
        )

        assertTrue(
            PortableBackupPolicy.validate(backup, "com.liuml.apptimelimiter") is
                PortableBackupValidationResult.Invalid,
        )
    }

    private fun sampleBackup() = PortableBackupV1(
        createdAtMillis = 1_700_000_000_000L,
        sourceVersionName = "0.11.13",
        sourceVersionCode = 52,
        rules = listOf(
            AppRule(
                packageName = "com.example.video",
                enabled = true,
                dailyEnabled = true,
                dailyLimitSeconds = 3_600L,
            ),
        ),
        groups = listOf(
            AppGroup(
                id = "social",
                name = "Social",
                packageNames = setOf("com.example.chat"),
            ),
        ),
        settings = PortableGlobalSettings(customTimeQuotes = listOf("Use time well")),
    )
}
