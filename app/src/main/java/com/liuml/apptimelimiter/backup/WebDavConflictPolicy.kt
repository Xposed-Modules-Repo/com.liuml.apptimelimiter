package com.liuml.apptimelimiter.backup

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class WebDavConflictChoice { KEEP_LOCAL, USE_REMOTE, CANCEL }

data class WebDavConflictSummary(
    val localCreatedAtMillis: Long,
    val remoteCreatedAtMillis: Long,
    val localRuleCount: Int,
    val remoteRuleCount: Int,
    val localGroupCount: Int,
    val remoteGroupCount: Int,
)

object WebDavConflictPolicy {
    fun summarize(local: PortableBackupV1, remote: PortableBackupV1): WebDavConflictSummary =
        WebDavConflictSummary(
            local.createdAtMillis,
            remote.createdAtMillis,
            local.rules.size,
            remote.rules.size,
            local.groups.size,
            remote.groups.size,
        )

    fun isConflict(local: PortableBackupV1, remote: PortableBackupV1): Boolean =
        digest(local) != digest(remote)

    private fun digest(backup: PortableBackupV1): String = MessageDigest.getInstance("SHA-256")
        .digest(PortableBackupCodec.encode(backup).toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
