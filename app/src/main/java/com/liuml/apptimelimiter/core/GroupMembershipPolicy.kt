package com.liuml.apptimelimiter.core

object GroupMembershipPolicy {
    fun hasConflict(
        targetGroupId: String,
        packageNames: Collection<String>,
        assignedGroupByPackage: Map<String, String?>,
    ): Boolean = packageNames.any { packageName ->
        assignedGroupByPackage[packageName]?.let { it != targetGroupId } == true
    }
}
