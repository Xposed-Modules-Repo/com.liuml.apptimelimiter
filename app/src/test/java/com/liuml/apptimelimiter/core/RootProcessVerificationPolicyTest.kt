package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.liuml.apptimelimiter.core.RootProcessVerificationPolicy.Result.*

class RootProcessVerificationPolicyTest {
    private val target = RootProcessVerificationPolicy.Target(
        "com.example.target", 0, 10123, setOf("com.example.target", "org.example.worker"),
    )
    private fun table(vararg rows: String) =
        "UID PID ARGS\n0 1 init second_stage\n" + rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n") +
            RootProcessVerificationPolicy.END_MARKER + "\n"
    private fun check(expected: RootProcessVerificationPolicy.Result, vararg rows: String) =
        assertEquals(expected, RootProcessVerificationPolicy.verify(target, 0, table(*rows)))

    @Test fun `main process is found`() = check(RUNNING, "10123 42 com.example.target")
    @Test fun `child alone is found`() = check(RUNNING, "10123 42 com.example.target:remote")
    @Test fun `manifest global process alone is found`() = check(RUNNING, "10123 42 org.example.worker")
    @Test fun `isolated child and manifest process are found`() {
        check(RUNNING, "99001 42 com.example.target:isolated")
        check(RUNNING, "90001 42 org.example.worker")
    }
    @Test fun `another Android user is ignored`() =
        check(ABSENT, "1010123 42 com.example.target", "1099001 43 com.example.target:isolated")
    @Test fun `secondary user uses full uid`() {
        val other = target.copy(userId = 10, uid = 1010123)
        assertEquals(RUNNING, RootProcessVerificationPolicy.verify(other, 0, table("1010123 42 org.example.worker")))
        assertEquals(ABSENT, RootProcessVerificationPolicy.verify(other, 0, table("10123 42 com.example.target")))
    }
    @Test fun `package prefix is not a package match`() =
        check(ABSENT, "10456 42 com.example.targetextra", "10456 43 com.example.target.extra")
    @Test fun `same uid unknown or shared process is inconclusive`() = check(UNKNOWN, "10123 42 native_worker")
    @Test fun `same name unexpected uid is inconclusive`() = check(UNKNOWN, "10456 42 com.example.target")
    @Test fun `arguments do not impersonate process name`() = check(ABSENT, "2000 42 sh com.example.target")
    @Test fun `clean complete table proves absence`() = check(ABSENT, "10456 42 com.other.app")
    @Test fun `failure empty and partial output cannot prove absence`() {
        for (output in listOf(null, "", "UID PID ARGS\n", table().removeSuffix("\n"), table().replace("0 1 init second_stage\n", ""))) {
            assertEquals(UNKNOWN, RootProcessVerificationPolicy.verify(target, 0, output))
        }
        assertEquals(UNKNOWN, RootProcessVerificationPolicy.verify(target, 1, table()))
        assertEquals(UNKNOWN, RootProcessVerificationPolicy.verify(target, -1, table()))
    }
    @Test fun `malformed rows diagnostics duplicate pids and unsupported format fail closed`() {
        for (row in listOf("permission denied", "u0_a123 42 com.example.target", "-1 42 x", "10123 0 x", "10123 42", "10123 2147483648 x", "10123 1 x")) {
            check(UNKNOWN, row)
        }
        assertEquals(UNKNOWN, RootProcessVerificationPolicy.verify(target, 0, table().replace("ARGS", "NAME")))
        check(UNKNOWN, "10456 42 other", "10456 42 other")
    }
    @Test fun `oversize output is rejected`() {
        assertEquals(UNKNOWN, RootProcessVerificationPolicy.verify(target, 0, "x".repeat(RootProcessVerificationPolicy.MAX_OUTPUT_BYTES + 1)))
    }
    @Test fun `target user uid metadata and shell inputs stay strict`() {
        for (invalid in listOf(
            target.copy(userId = -1), target.copy(userId = 10), target.copy(uid = -1),
            target.copy(uid = 1000), target.copy(uid = 99000), target.copy(packageName = "com.example;id"),
            target.copy(manifestProcesses = emptySet()), target.copy(manifestProcesses = setOf("worker;id")),
        )) {
            assertFalse(RootProcessVerificationPolicy.isValid(invalid))
            assertEquals(UNKNOWN, RootProcessVerificationPolicy.verify(invalid, 0, table()))
        }
    }
}
