package com.liuml.apptimelimiter.migration

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationPayloadCodecTest {
    @Test
    fun typedPayloadRoundTripsWithoutRuntimeState() {
        val payload = samplePayload()

        val decoded = MigrationPayloadCodec.decode(MigrationPayloadCodec.encode(payload))

        assertEquals(payload.createdAtMillis, decoded.createdAtMillis)
        assertEquals(payload.rulesetGeneration, decoded.rulesetGeneration)
        assertEquals("abcd", decoded.childLock["verifier"])
        assertEquals(120_000L, decoded.usageStatistics["2026-08-28.com.example.video.duration_ms"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRuleRuntimeState() {
        MigrationPayloadCodec.encode(
            samplePayload().copy(
                rules = samplePayload().rules +
                    ("group.social.runtime_cooldown_ends_at" to 123L),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHookHeartbeatState() {
        MigrationPayloadCodec.encode(
            samplePayload().copy(
                usageStatistics = mapOf("heartbeat.com.example.video" to 123L),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownChildLockState() {
        MigrationPayloadCodec.encode(
            samplePayload().copy(childLock = mapOf("temporary_override" to "secret")),
        )
    }

    private fun samplePayload() = MigrationPayload(
        createdAtMillis = 1_700_000_000_000L,
        sourceVersionName = "0.11.13",
        sourceVersionCode = 52,
        rulesetGeneration = 7L,
        rules = mapOf(
            "storage.primary_initialized" to true,
            "storage.ruleset_generation" to 7L,
            "configured_packages" to setOf("com.example.video"),
        ),
        childLock = mapOf(
            "enabled" to true,
            "salt" to "1234",
            "verifier" to "abcd",
            "iterations" to 210_000,
        ),
        usageStatistics = mapOf(
            "2026-08-28.com.example.video.duration_ms" to 120_000L,
        ),
    )
}
