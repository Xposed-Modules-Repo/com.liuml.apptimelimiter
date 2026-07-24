package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMembershipPolicyTest {
    @Test
    fun `unassigned and same-group members are accepted`() {
        assertFalse(
            GroupMembershipPolicy.hasConflict(
                targetGroupId = "group-a",
                packageNames = listOf("app.one", "app.two"),
                assignedGroupByPackage = mapOf(
                    "app.one" to null,
                    "app.two" to "group-a",
                ),
            ),
        )
    }

    @Test
    fun `member assigned to another group is rejected`() {
        assertTrue(
            GroupMembershipPolicy.hasConflict(
                targetGroupId = "group-a",
                packageNames = listOf("app.one"),
                assignedGroupByPackage = mapOf("app.one" to "group-b"),
            ),
        )
    }

    @Test
    fun `new member with personal settings is rejected`() {
        assertTrue(
            GroupMembershipPolicy.hasNewMemberWithPersonalConfiguration(
                requestedMembers = listOf("existing", "new"),
                existingMembers = listOf("existing"),
                packagesWithPersonalConfiguration = setOf("new"),
            ),
        )
    }

    @Test
    fun `legacy member with dormant personal settings remains editable`() {
        assertFalse(
            GroupMembershipPolicy.hasNewMemberWithPersonalConfiguration(
                requestedMembers = listOf("existing"),
                existingMembers = listOf("existing"),
                packagesWithPersonalConfiguration = setOf("existing"),
            ),
        )
    }
}
