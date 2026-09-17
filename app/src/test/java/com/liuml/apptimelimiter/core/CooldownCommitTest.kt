package com.liuml.apptimelimiter.core

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class CooldownCommitTest {
    @Test fun failedCommitPoisonsSubsequentWritesEvenIfMemoryChanged() {
        var attempts = 0
        val prefs = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, _, _ -> null } as SharedPreferences
        val editor = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _, method, _ ->
            if (method.name == "commit") { attempts++; false } else null
        } as SharedPreferences.Editor
        repeat(2) {
            try {
                CooldownClock.commit(prefs, editor)
                fail("Must fail closed")
            } catch (_: IllegalStateException) { }
        }
        assertEquals(1, attempts)
        try {
            CooldownClock.requireHealthy(prefs)
            fail("A failed write must also block subsequent reads")
        } catch (_: IllegalStateException) { }
    }
}
