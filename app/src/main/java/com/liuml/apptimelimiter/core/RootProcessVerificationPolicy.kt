package com.liuml.apptimelimiter.core

/** A process-table snapshot proves absence only within its user and observation instant. */
object RootProcessVerificationPolicy {
    const val MAX_OUTPUT_BYTES = 1_048_576
    const val END_MARKER = "__TIMESTOP_PS_COMPLETE__"
    const val COMMAND = "/system/bin/ps -A -n -w -o UID,PID,ARGS && /system/bin/echo __TIMESTOP_PS_COMPLETE__"
    private const val USER_RANGE = 100_000
    private val whitespace = Regex("\\s+")
    private val processName = Regex("[A-Za-z_][A-Za-z0-9_.]*(?::[A-Za-z0-9_.]+)?")

    data class Target(val packageName: String, val userId: Int, val uid: Int, val manifestProcesses: Set<String>)
    enum class Result { RUNNING, ABSENT, UNKNOWN }

    fun isValid(target: Target): Boolean =
        PackageNamePolicy.isValid(target.packageName) && target.userId >= 0 &&
            target.uid >= 0 && target.uid / USER_RANGE == target.userId &&
            target.uid % USER_RANGE in 10_000..19_999 &&
            target.manifestProcesses.isNotEmpty() && target.manifestProcesses.all {
                it.length <= 255 && processName.matches(it)
            }

    fun verify(target: Target, exitCode: Int, output: String?): Result {
        if (!isValid(target) || exitCode != 0 || output == null ||
            output.length > MAX_OUTPUT_BYTES || !output.endsWith("$END_MARKER\n")
        ) return Result.UNKNOWN
        val lines = output.lineSequence().toList()
        if (lines.firstOrNull()?.trim()?.split(whitespace) != listOf("UID", "PID", "ARGS")) {
            return Result.UNKNOWN
        }
        var sawInit = false
        var running = false
        var ambiguous = false
        val pids = HashSet<Int>()
        for (line in lines.drop(1).dropLast(2)) {
            val fields = line.trim().split(whitespace, limit = 3)
            if (fields.size != 3) return Result.UNKNOWN
            val uid = fields[0].toIntOrNull() ?: return Result.UNKNOWN
            val pid = fields[1].toIntOrNull() ?: return Result.UNKNOWN
            if (uid < 0 || pid <= 0 || !pids.add(pid) || fields[2].isBlank()) return Result.UNKNOWN
            if (uid == 0 && pid == 1) sawInit = true
            if (uid / USER_RANGE != target.userId) continue
            val name = fields[2].split(whitespace, limit = 2)[0]
            val named = name == target.packageName ||
                name.startsWith("${target.packageName}:") || name in target.manifestProcesses
            when {
                named && (uid == target.uid || uid % USER_RANGE in 90_000..99_999) -> running = true
                // Native/renamed or shared-UID processes cannot safely prove package absence.
                uid == target.uid || named -> ambiguous = true
            }
        }
        return when {
            !sawInit -> Result.UNKNOWN
            running -> Result.RUNNING
            ambiguous -> Result.UNKNOWN
            else -> Result.ABSENT
        }
    }
}
