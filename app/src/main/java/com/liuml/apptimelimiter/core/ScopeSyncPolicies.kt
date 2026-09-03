package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.ProtectionMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Pure policy shared by the repository coordinator and unit tests. */
object DesiredScopePolicy {
    fun compute(
        rules: Collection<AppRule>,
        groups: Collection<AppGroup>,
        modulePackageName: String,
    ): Set<String> {
        val assignedGroupByPackage = buildMap {
            groups.forEach { group ->
                group.packageNames.forEach { packageName -> putIfAbsent(packageName, group) }
            }
        }
        val ruleByPackage = rules.associateBy(AppRule::packageName)
        return (ruleByPackage.keys + assignedGroupByPackage.keys)
            .asSequence()
            .filter(String::isNotBlank)
            .filterNot { it == modulePackageName }
            .filter { packageName ->
                RuleActivationPolicy.hasEffectiveRule(
                    rule = ruleByPackage[packageName] ?: AppRule(packageName),
                    assignedGroup = assignedGroupByPackage[packageName],
                )
            }
            .toSortedSet()
    }

    fun generation(packages: Set<String>, protectionModeGeneration: Long): String {
        val source = buildString {
            append(protectionModeGeneration)
            packages.sorted().forEach { packageName ->
                append('\n')
                append(packageName)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

data class ScopeReconciliationPlan(
    val requestPackages: Set<String>,
    val removePackages: Set<String>,
    val keepPackages: Set<String>,
)

object ScopeReconciliationPolicy {
    fun resolve(
        desiredPackages: Set<String>,
        actualPackages: Set<String>,
        managedPackages: Set<String>,
        protectionMode: ProtectionMode,
        frameworkReadable: Boolean,
    ): ScopeReconciliationPlan {
        if (!frameworkReadable) {
            return ScopeReconciliationPlan(emptySet(), emptySet(), emptySet())
        }
        return ScopeReconciliationPlan(
            requestPackages = if (protectionMode == ProtectionMode.XPOSED) {
                desiredPackages - actualPackages
            } else {
                emptySet()
            },
            removePackages = (managedPackages - desiredPackages).intersect(actualPackages),
            keepPackages = desiredPackages.intersect(actualPackages),
        )
    }
}

data class ScopeTargetGeneration(
    val fingerprint: String,
    val counter: Long,
)

object ScopeTargetGenerationPolicy {
    fun resolve(
        previousFingerprint: String?,
        previousCounter: Long,
        currentFingerprint: String,
    ): ScopeTargetGeneration = ScopeTargetGeneration(
        fingerprint = currentFingerprint,
        counter = if (previousFingerprint == currentFingerprint) {
            previousCounter.coerceAtLeast(1L)
        } else {
            (previousCounter + 1L).coerceAtLeast(1L)
        },
    )
}

data class ScopeApprovalResult(
    val approvedPackages: Set<String>,
    val pendingPackages: Set<String>,
)

object ScopeApprovalPolicy {
    fun resolve(
        requestedPackages: Set<String>,
        approvedPackages: Set<String>,
    ): ScopeApprovalResult {
        val accepted = approvedPackages.intersect(requestedPackages)
        return ScopeApprovalResult(
            approvedPackages = accepted,
            pendingPackages = requestedPackages - accepted,
        )
    }
}
