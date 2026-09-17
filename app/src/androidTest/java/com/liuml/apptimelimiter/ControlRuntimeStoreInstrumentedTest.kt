package com.liuml.apptimelimiter

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.liuml.apptimelimiter.core.ControlRuntimeState
import com.liuml.apptimelimiter.core.ControlSessionToken
import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.security.ControlRuntimeStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Uses the exact durable machine adapter called by RuleProvider, never production preferences/PIN. */
class ControlRuntimeStoreInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = "test_control_runtime_${UUID.randomUUID()}"
    private val isolated = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(storage, mode)
    }
    private val prefs get() = isolated.getSharedPreferences(ControlRuntimeStore.PREFS_NAME, Context.MODE_PRIVATE)
    private val token = ControlSessionToken("test.app", "group", "session", 1, 2, ProtectionMode.XPOSED, 3, 47)
    @After fun cleanup() { assertTrue("Isolated preferences must be deleted", base.deleteSharedPreferences(storage)) }

    @Test fun repeatedClaimKeepsIncidentAndProgressAcrossInstances() {
        val store = ControlRuntimeStore(prefs, 7)
        val first = store.claim(token, "owner", 100)
        assertNotNull(first)
        assertEquals(first, store.claim(token, "owner", 101))
        assertTrue(store.transition(token, "owner", ControlRuntimeState.WAITING_PARENT_AUTH, 102))
        val expected = store.read(token, 103)
        assertNotNull(expected)
        val reconstructed = ControlRuntimeStore(prefs, 7)
        assertEquals(expected, reconstructed.claim(token, "contender", 104))
        assertEquals("owner", reconstructed.read(token, 105)?.incident)
        assertEquals(ControlRuntimeState.WAITING_PARENT_AUTH, reconstructed.read(token, 105)?.state)
        assertFalse(reconstructed.transition(token, "contender", ControlRuntimeState.WAITING_AD, 106))
        assertEquals(expected, reconstructed.read(token, 107))
    }

    @Test fun foregroundGenerationIsSerializedAndRestoredWithoutAcceptingOldToken() {
        assertNotNull(ControlRuntimeStore(prefs, 7).claim(token, "owner", 100))
        val raw = JSONObject(prefs.getString(token.packageName, null)!!)
        assertEquals(47L, raw.getLong("foregroundGeneration"))
        val restored = ControlRuntimeStore(base.getSharedPreferences(storage, Context.MODE_PRIVATE), 7)
        assertEquals(token, restored.read(token, 101)?.token)
        assertEquals(47L, restored.read(token, 101)?.token?.foregroundGeneration)
        val stale = token.copy(foregroundGeneration = 46)
        assertNull(restored.read(stale, 102))
        assertFalse(restored.transition(stale, "owner", ControlRuntimeState.WAITING_PARENT_AUTH, 102))
        assertEquals(ControlRuntimeState.LIMIT_CLAIMED, restored.read(token, 103)?.state)
    }

    @Test fun cancellationAllowsSameTokenToClaimNewIncidentAndRejectsOldIncident() {
        val store = ControlRuntimeStore(prefs, 7)
        assertNotNull(store.claim(token, "old", 100))
        assertTrue(store.transition(token, "old", ControlRuntimeState.CANCELLED, 101))
        val restored = ControlRuntimeStore(prefs, 7)
        assertEquals(ControlRuntimeState.CANCELLED, restored.read(token, 102)?.state)
        val reclaimed = restored.claim(token, "new", 103)
        assertNotNull(reclaimed)
        assertEquals("new", reclaimed?.incident)
        assertEquals(ControlRuntimeState.LIMIT_CLAIMED, reclaimed?.state)
        assertFalse(restored.transition(token, "old", ControlRuntimeState.WAITING_PARENT_AUTH, 104))
        assertTrue(restored.transition(token, "new", ControlRuntimeState.WAITING_PARENT_AUTH, 105))
    }

    @Test fun changedBootRejectsReadAndTransitionButAllowsFreshClaim() {
        assertNotNull(ControlRuntimeStore(prefs, 7).claim(token, "before-boot", 100))
        val afterBoot = ControlRuntimeStore(prefs, 8)
        assertNull(afterBoot.read(token, 101))
        assertFalse(afterBoot.transition(token, "before-boot", ControlRuntimeState.WAITING_PARENT_AUTH, 102))
        assertNull(ControlRuntimeStore(prefs, -1).read(token, 103))
        assertNull(ControlRuntimeStore(prefs, -1).claim(token, "invalid-boot", 103))
        assertEquals("after-boot", afterBoot.claim(token, "after-boot", 104)?.incident)
        assertFalse(afterBoot.transition(token, "before-boot", ControlRuntimeState.WAITING_PARENT_AUTH, 105))
    }

    @Test fun actualPinAndAdTransitionsSurviveAuthorityReconstruction() {
        var store = ControlRuntimeStore(prefs, 7)
        assertNotNull(store.claim(token, "incident", 100))
        assertTrue(store.transition(token, "incident", ControlRuntimeState.EXECUTING_RESTRICTION, 101))
        assertTrue(store.transition(token, "incident", ControlRuntimeState.RESTRICTION_VISIBLE, 102))
        assertTrue(store.transition(token, "incident", ControlRuntimeState.WAITING_PARENT_AUTH, 103))
        assertTrue(store.transition(token, "incident", ControlRuntimeState.WAITING_AD, 104))
        store = ControlRuntimeStore(prefs, 7)
        assertEquals(token, store.read(token, 105)?.token)
        // Ad duration exceeding two minutes does not consume the PIN reservation lifetime.
        assertTrue(store.transition(token, "incident", ControlRuntimeState.OVERRIDE_PENDING, 180_104))
        assertTrue(store.transition(token, "incident", ControlRuntimeState.OVERRIDE_ACTIVE, 180_105))
        assertEquals(ControlRuntimeState.OVERRIDE_ACTIVE, ControlRuntimeStore(prefs, 7).read(token, 180_106)?.state)
    }

    @Test fun adFailureRestoresRestrictionAndLateCallbackCannotGrant() {
        val store = ControlRuntimeStore(prefs, 7)
        store.claim(token, "incident", 100)
        assertTrue(store.transition(token, "incident", ControlRuntimeState.EXECUTING_RESTRICTION, 101))
        assertTrue(store.transition(token, "incident", ControlRuntimeState.RESTRICTION_VISIBLE, 102))
        assertTrue(store.transition(token, "incident", ControlRuntimeState.WAITING_AD, 103))
        assertTrue(store.transition(token, "incident", ControlRuntimeState.EXECUTING_RESTRICTION, 104))
        assertTrue(store.transition(token, "incident", ControlRuntimeState.RESTRICTION_VISIBLE, 105))
        assertFalse(store.transition(token, "incident", ControlRuntimeState.OVERRIDE_PENDING, 106))
        assertFalse(store.transition(token.copy(ruleVersion = 2), "incident", ControlRuntimeState.WAITING_AD, 107))
        assertFalse(store.transition(token.copy(foregroundGeneration = 48), "incident", ControlRuntimeState.WAITING_AD, 107))
        assertFalse(store.transition(token, "other-event", ControlRuntimeState.WAITING_AD, 107))
    }

    @Test fun expiryRebootAndExplicitExitReleaseOwnership() {
        val store = ControlRuntimeStore(prefs, 7)
        store.claim(token, "incident", 100)
        assertNull(ControlRuntimeStore(prefs, 8).read(token, 101))
        assertNull(store.read(token, 99))
        assertNull(store.read(token, 100 + ControlRuntimeStore.LEASE_MILLIS))
        assertNotNull(store.claim(token, "new", 101 + ControlRuntimeStore.LEASE_MILLIS))
        assertTrue(store.transition(token, "new", ControlRuntimeState.CANCELLED, 102 + ControlRuntimeStore.LEASE_MILLIS))
        assertNotNull(store.claim(token.copy(sessionId = "next"), "next-event", 103 + ControlRuntimeStore.LEASE_MILLIS))
        assertFalse(store.transition(token, "new", ControlRuntimeState.EXECUTING_RESTRICTION, 104 + ControlRuntimeStore.LEASE_MILLIS))
    }

    @Test fun verifiedNewSessionCanTakeOverButOldCallbackCannotTransition() {
        val store = ControlRuntimeStore(prefs, 7)
        store.claim(token, "old", 100)
        val next = token.copy(sessionId = "new")
        assertNull(store.claim(next, "new-event", 101))
        assertNull(store.claim(next, "new-event", 101, replaceSession = false))
        assertEquals("old", store.read(token, 101)?.incident)
        assertNull(store.read(next, 101))
        assertNotNull(store.claim(next, "new-event", 102, replaceSession = true))
        assertNull(store.read(token, 103))
        assertFalse(store.transition(token, "old", ControlRuntimeState.WAITING_PARENT_AUTH, 103))
        assertFalse(store.transition(next, "old", ControlRuntimeState.WAITING_PARENT_AUTH, 103))
        assertTrue(store.transition(next, "new-event", ControlRuntimeState.WAITING_PARENT_AUTH, 104))
    }

    @Test fun commitFailurePoisonsAllInstancesAndRollsBackMemoryPublication() {
        var fail = false
        val real = prefs
        val failing = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    // Preserve the wrapper through the store's fluent putString(...).commit().
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        val committed = editor.commit() // Simulate memory mutation even on failure.
                        return committed && !fail
                    }
                }
            }
        }
        val store = ControlRuntimeStore(failing, 7)
        assertNotNull(store.claim(token, "incident", 100))
        fail = true
        assertFalse(store.transition(token, "incident", ControlRuntimeState.WAITING_PARENT_AUTH, 101))
        assertNull(store.read(token, 102))
        assertNull(ControlRuntimeStore(failing, 7).read(token, 102))
        assertNull(store.claim(token, "second", 103))
        assertEquals(ControlRuntimeState.LIMIT_CLAIMED, ControlRuntimeStore(real, 7).read(token, 104)?.state)
    }

    @Test fun failedInitialClaimCannotBeReadEvenWhenMemoryChangedAndRollbackFails() {
        val failing = FailedCommitPreferences(prefs)
        failing.fail = true
        val store = ControlRuntimeStore(failing, 7)
        assertNull(store.claim(token, "uncommitted", 100))
        assertTrue("Failure path must actually invoke the wrapped commit", failing.failedCommits >= 1)
        assertEquals("uncommitted", JSONObject(prefs.getString(token.packageName, null)!!).getString("incident"))
        assertNull(store.read(token, 101))
        assertNull(ControlRuntimeStore(failing, 7).read(token, 101))
        assertFalse(store.transition(token, "uncommitted", ControlRuntimeState.WAITING_PARENT_AUTH, 102))
        assertNull(store.claim(token, "retry", 103))
    }

    @Test fun failedTransitionCannotPublishMutatedStateEvenWhenRollbackFails() {
        val failing = FailedCommitPreferences(prefs)
        val store = ControlRuntimeStore(failing, 7)
        assertNotNull(store.claim(token, "owner", 100))
        failing.fail = true
        assertFalse(store.transition(token, "owner", ControlRuntimeState.WAITING_PARENT_AUTH, 101))
        assertTrue(failing.failedCommits >= 1)
        assertEquals("WAITING_PARENT_AUTH", JSONObject(prefs.getString(token.packageName, null)!!).getString("state"))
        assertNull(store.read(token, 102))
        val reconstructed = ControlRuntimeStore(failing, 7)
        assertNull(reconstructed.read(token, 102))
        assertFalse(reconstructed.transition(token, "owner", ControlRuntimeState.WAITING_AD, 103))
        assertNull(reconstructed.claim(token, "retry", 104))
    }

    @Test fun failedIdempotentClaimRefreshDoesNotReturnPreviousSuccess() {
        val failing = FailedCommitPreferences(prefs)
        val store = ControlRuntimeStore(failing, 7)
        assertNotNull(store.claim(token, "owner", 100))
        failing.fail = true
        assertNull(store.claim(token, "owner", 101))
        assertTrue(failing.failedCommits >= 1)
        assertNull(store.read(token, 102))
        assertNull(ControlRuntimeStore(failing, 7).read(token, 102))
    }

    /** First failed commit publishes the staged values; subsequent failures refuse rollback.
     * The backing file is disposable. This injects a false return after real memory mutation;
     * it does not attempt to reproduce a device filesystem failure.
     */
    private class FailedCommitPreferences(private val real: SharedPreferences) : SharedPreferences by real {
        var fail = false
        var failedCommits = 0
            private set

        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    editor.remove(key)
                    return this
                }
                override fun commit(): Boolean {
                    if (!fail) return editor.commit()
                    failedCommits++
                    if (failedCommits == 1) assertTrue("Test mutation must succeed", editor.commit())
                    return false
                }
            }
        }
    }

    @Test fun concurrentClaimsHaveOneDurableIncidentOwner() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..16).map { index -> Callable {
                ControlRuntimeStore(prefs, 7).claim(token, "incident-$index", 100)?.incident
            } }).map { it.get() }
            assertEquals(1, results.filterNotNull().toSet().size)
            assertEquals(16, results.filterNotNull().size)
            assertEquals(results.first(), ControlRuntimeStore(prefs, 7).read(token, 101)?.incident)
        } finally { pool.shutdownNow() }
    }
}
