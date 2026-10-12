package com.liuml.apptimelimiter.core

import org.junit.Assert.*
import org.junit.Test
import com.liuml.apptimelimiter.nonroot.TargetTaskSnapshot

class TargetTaskExitPolicyTest {
    @Test fun windowedAndUnknownNeverAuthorizeHome() {
        listOf(0, 2, 3, 4, 5, 6, 100).forEach {
            assertFalse(TargetTaskExitPolicy.mayGoHome(TargetTaskExitPolicy.mode(it)))
        }
        assertFalse(TargetTaskExitPolicy.mayGoHome(TargetTaskExitPolicy.mode(1, multiWindow = true)))
        assertTrue(TargetTaskExitPolicy.mayGoHome(TargetTaskExitPolicy.mode(1)))
    }

    @Test fun gamePackageAnotherTaskAndAnotherUserCannotBeClosed() {
        val pkg = "com.ss.android.ugc.aweme"
        assertTrue(TargetTaskExitPolicy.matches(pkg, 321, 0, pkg, 321, 0))
        assertFalse(TargetTaskExitPolicy.matches(pkg, 321, 0, "com.tencent.tmgp.sgame", 321, 0))
        assertFalse(TargetTaskExitPolicy.matches(pkg, 321, 0, pkg, 322, 0))
        assertFalse(TargetTaskExitPolicy.matches(pkg, 321, 0, pkg, 321, 10))
        assertFalse(TargetTaskExitPolicy.matches(pkg, -1, 0, pkg, -1, 0))
        assertFalse(TargetTaskExitPolicy.matches("", 321, 0, "", 321, 0))
        assertFalse(TargetTaskExitPolicy.matches(pkg, 321, 0, null, 321, 0))
    }

    @Test fun malformedOrAmbiguousTaskSnapshotFailsClosed() {
        listOf(null, "", "closed", "-1|5", "4|bad", "1|5|2", "TASK:1|5").forEach {
            assertNull(TargetTaskSnapshot.parse(it))
        }
        assertEquals(TargetTaskSnapshot(123, TargetWindowMode.WINDOWED), TargetTaskSnapshot.parse("123|5"))
        assertEquals(TargetWindowMode.UNKNOWN, TargetTaskSnapshot.parse("123|0")?.windowMode)
    }
}
