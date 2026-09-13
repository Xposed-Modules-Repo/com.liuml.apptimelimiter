package com.liuml.apptimelimiter.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavConflictPolicyTest {
    private fun backup(created: Long, rules: List<com.liuml.apptimelimiter.data.AppRule> = emptyList()) =
        PortableBackupV1(created, "0.11.22", 61, rules, emptyList(), PortableGlobalSettings())

    @Test fun `same content is not a conflict`() {
        assertFalse(WebDavConflictPolicy.isConflict(backup(1), backup(1)))
    }

    @Test fun `changed content is a conflict`() {
        assertTrue(WebDavConflictPolicy.isConflict(backup(1), backup(2)))
    }
}
