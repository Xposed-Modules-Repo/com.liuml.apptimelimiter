package com.liuml.apptimelimiter

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.liuml.apptimelimiter.core.ControlRuntimeState
import com.liuml.apptimelimiter.core.ControlSessionToken
import com.liuml.apptimelimiter.core.TemporaryOverrideIdentity
import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.security.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Actual production SQLite implementation, isolated from the installed app's authority and PIN. */
class ManagerControlDatabaseInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "test_control_sql_${UUID.randomUUID()}"
    private val preferenceNames = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "${prefix}_$name"
            preferenceNames.add(isolated)
            return base.getSharedPreferences(isolated, mode)
        }
    }
    private var enabled = true
    private var db = ManagerControlDatabase(context, "$prefix.db") { enabled }
    private val token = ControlSessionToken("test.app", "group", "session", 1, 2, ProtectionMode.XPOSED, 3, 0)
    private val identity = TemporaryOverrideIdentity("test.app", "session", 1, 2, 3)
    private val runtime get() = ControlRuntimeStore(db, 7)
    private fun now() = SystemClock.elapsedRealtime()
    private fun wall() = System.currentTimeMillis()
    private fun <T> atomic(block: () -> T): T = ParentAuthStore.atomicOperation({ true }, block)

    private fun prepare() {
        ParentAuthStore.initializeForTests(db, 7)
        atomic {
            assertNotNull(runtime.claim(token, "incident", now()))
            assertTrue(runtime.transition(token, "incident", ControlRuntimeState.WAITING_PARENT_AUTH, now()))
            ParentAuthStore.issue("private-token", identity, "incident", "QUOTA", wall())
            assertNotNull(ParentAuthStore.consumeForUi("private-token", wall()))
        }
    }

    private fun complete() = atomic {
        ParentAuthStore.complete("private-token", true, 60_000L, wall(), now(),
            enforceDailyAd = true, deferActivation = true, isIdentityCurrent = {
                runtime.transition(token, "incident", ControlRuntimeState.OVERRIDE_PENDING, now())
            })
    }

    @After fun cleanup() {
        ParentAuthStore.resetForTests()
        db.closeForTests()
        base.deleteDatabase("$prefix.db")
        preferenceNames.forEach { base.deleteSharedPreferences(it) }
    }

    @Test fun finalCommitFailureRollsBackStatePinReceiptQuotaAndOutbox() {
        prepare()
        val before = db.pendingDiagnostics()
        db.beforeCommitForTests = { throw IllegalStateException("injected final commit failure") }
        try { complete(); fail("Must not publish success") } catch (_: IllegalStateException) { }
        db.beforeCommitForTests = null
        assertEquals(ControlRuntimeState.WAITING_PARENT_AUTH, runtime.read(token, now())?.state)
        assertEquals(ParentAuthStatus.WAITING, ParentAuthStore.challenge("private-token")?.status)
        assertNull(ParentAuthStore.pendingOverride(identity, now()))
        assertFalse(ParentAuthStore.isAdRequired(wall(), now()))
        assertEquals(before, db.pendingDiagnostics())
        assertNotNull("Receipt must also have rolled back", complete())
    }

    @Test fun deniedProviderResultRollsBackBothAuthorities() {
        prepare()
        val result = ParentAuthStore.atomicOperation({ it }) {
            assertTrue(runtime.transition(token, "incident", ControlRuntimeState.OVERRIDE_PENDING, now()))
            ParentAuthStore.markVerifiedWaitingForAd("private-token", wall())
            false
        }
        assertFalse(result)
        assertEquals(ControlRuntimeState.WAITING_PARENT_AUTH, runtime.read(token, now())?.state)
        assertEquals(ParentAuthStatus.WAITING, ParentAuthStore.challenge("private-token")?.status)
    }

    @Test fun consumedChallengeAndPendingGrantSurviveDatabaseReopen() {
        prepare()
        db.closeForTests()
        db = ManagerControlDatabase(context, "$prefix.db") { enabled }
        ParentAuthStore.initializeForTests(db, 7)
        assertTrue(ParentAuthStore.challenge("private-token")!!.uiConsumed)
        assertNull(ParentAuthStore.consumeForUi("private-token", wall()))
        val granted = complete()!!
        db.closeForTests()
        db = ManagerControlDatabase(context, "$prefix.db") { enabled }
        ParentAuthStore.initializeForTests(db, 7)
        assertEquals(granted.parentOverride, complete()?.parentOverride)
        assertEquals(granted.parentOverride, ParentAuthStore.pendingOverride(identity, now()))
    }

    @Test fun activationFailureRestoresReservationAndRetryKeepsFixedDeadline() {
        prepare(); assertNotNull(complete())
        val pending = ParentAuthStore.pendingOverride(identity, now())!!
        val outbox = db.pendingDiagnostics()
        db.beforeCommitForTests = { throw IllegalStateException("injected") }
        try {
            atomic {
                assertTrue(runtime.transition(token, "incident", ControlRuntimeState.OVERRIDE_ACTIVE, now()))
                assertNotNull(ParentAuthStore.activateOverride(identity, true, now(), true))
            }
            fail("Must fail closed")
        } catch (_: IllegalStateException) { }
        db.beforeCommitForTests = null
        assertEquals(ControlRuntimeState.OVERRIDE_PENDING, runtime.read(token, now())?.state)
        assertEquals(pending, ParentAuthStore.pendingOverride(identity, now()))
        assertEquals(outbox, db.pendingDiagnostics())
        val activated = atomic {
            assertTrue(runtime.transition(token, "incident", ControlRuntimeState.OVERRIDE_ACTIVE, now()))
            ParentAuthStore.activateOverride(identity, true, now(), true)!!
        }
        assertEquals(activated, ParentAuthStore.activateOverride(identity, true, now() + 1_000, true))
        assertEquals(1, db.preferences("parent_auth_runtime").getInt("quota_count", 0))
        assertTrue(ParentAuthStore.isAdRequired(wall(), now()))
    }

    @Test fun foregroundAndPendingDeadlineAreStillMandatory() {
        prepare(); complete()
        val pending = ParentAuthStore.pendingOverride(identity, now())!!
        assertNull(ParentAuthStore.activateOverride(identity, true, now(), false))
        assertNull(ParentAuthStore.activateOverride(identity.copy(processSessionId = "stale"), true, now(), true))
        assertNull(ParentAuthStore.activateOverride(identity, true, pending.expiresAtElapsedMillis, true))
        assertEquals(0, db.preferences("parent_auth_runtime").getInt("quota_count", 0))
    }

    @Test fun outboxIsPrivateBoundedAcknowledgedAndDisabledWithDiagnostics() {
        prepare()
        val rows = db.pendingDiagnostics()
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.incidentId != "incident" && !it.toString().contains("private-token") })
        assertEquals(rows, db.pendingDiagnostics())
        db.acknowledgeDiagnostics(rows.map { it.id })
        db.acknowledgeDiagnostics(rows.map { it.id })
        assertTrue(db.pendingDiagnostics().isEmpty())
        enabled = false
        db.refreshDiagnosticsSetting()
        complete()
        assertTrue(db.pendingDiagnostics().isEmpty())
        enabled = true
        db.refreshDiagnosticsSetting()
        db.emit("test.app", "incident", RuntimeDiagnosticStage.PIN_ACTIVATED)
        db.discardDiagnostics()
        assertTrue(db.pendingDiagnostics().isEmpty())
    }

    @Test fun migrationMarkerNeverReimportsLegacyState() {
        val legacy = context.getSharedPreferences(ControlRuntimeStore.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(legacy.edit().putString("should-not-import", "old").commit())
        db.closeForTests()
        db = ManagerControlDatabase(context, "$prefix.db") { enabled }
        assertFalse(db.preferences(ControlRuntimeStore.PREFS_NAME).contains("should-not-import"))
        assertEquals("old", legacy.getString("should-not-import", null))
    }

    @Test fun rebootInvalidatesPersistedChallengeAndAllowance() {
        prepare(); complete()
        ParentAuthStore.initializeForTests(db, 8)
        assertNull(ParentAuthStore.challenge("private-token"))
        assertNull(ParentAuthStore.pendingOverride(identity, now()))
        assertNull(ControlRuntimeStore(db, 8).read(token, now()))
    }

    @Test fun parallelRuntimeClaimsAndParentTransactionsCannotReverseLockOrder() {
        prepare()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..32).map { index -> java.util.concurrent.Callable {
                if (index % 2 == 0) atomic { runtime.read(token, now())?.incident }
                else runtime.claim(token, "contender-$index", now())?.incident
            } }
            val results = pool.invokeAll(tasks, 10, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("No lock inversion or starvation", results.none { it.isCancelled })
            assertTrue(results.all { it.get() == "incident" })
        } finally { pool.shutdownNow() }
    }

    @Test fun initialMigrationImportsBothNamespacesAndDoesNotDoubleWrite() {
        db.closeForTests()
        base.deleteDatabase("$prefix.db")
        val legacyRuntime = context.getSharedPreferences(ControlRuntimeStore.PREFS_NAME, Context.MODE_PRIVATE)
        assertNotNull(ControlRuntimeStore(legacyRuntime, 7).claim(token, "migrated", now()))
        val legacyAuth = context.getSharedPreferences("parent_auth_runtime", Context.MODE_PRIVATE)
        assertTrue(legacyAuth.edit().putString("quota_day", java.time.LocalDate.now().toString())
            .putInt("quota_count", 1).commit())
        val original = legacyRuntime.all.toMap()
        db = ManagerControlDatabase(context, "$prefix.db") { enabled }
        assertEquals("migrated", runtime.read(token, now())?.incident)
        ParentAuthStore.initializeForTests(db, 7)
        assertTrue(ParentAuthStore.isAdRequired(wall(), now()))
        assertTrue(runtime.transition(token, "migrated", ControlRuntimeState.WAITING_PARENT_AUTH, now()))
        assertEquals(original, legacyRuntime.all)
    }

    @Test fun queuedCallerTimestampCannotExpireOrReplaceTheCurrentOwner() {
        var clock = 100L
        val store = ControlRuntimeStore(db, 7) { clock }
        assertNotNull(store.claim(token, "owner", 100L))
        clock = 200L
        assertTrue(store.transition(token, "owner", ControlRuntimeState.WAITING_PARENT_AUTH, 200L))
        // A queued caller sampled 150 before the writer at 200 acquired the lock first.
        clock = 250L
        assertEquals("owner", store.read(token, 150L)?.incident)
        val claimed = store.claim(token, "contender", 150L)
        assertEquals("owner", claimed?.incident)
        assertEquals(ControlRuntimeState.WAITING_PARENT_AUTH, claimed?.state)
        assertTrue(store.transition(token, "owner", ControlRuntimeState.WAITING_AD, 150L))
        val persisted = org.json.JSONObject(db.preferences(ControlRuntimeStore.PREFS_NAME).getString(token.packageName, null)!!)
        assertEquals(250L, persisted.getLong("saved"))
        assertEquals(250L + ControlRuntimeStore.LEASE_MILLIS, persisted.getLong("expires"))
        clock = persisted.getLong("expires")
        assertNull("A genuinely expired lease must still be rejected", store.read(token, 150L))
        clock = 249L
        assertNull("Actual monotonic clock rollback must still fail closed", store.read(token, 150L))
    }

    @Test fun closedMigrationGateDoesNotReadRepositoryOrOpenDatabase() {
        val blockedName = "${prefix}_blocked.db"
        var callbackRead = false
        val before = preferenceNames.toSet()
        try {
            ManagerControlDatabase(context, blockedName, migrationReady = { false },
                diagnosticsEnabled = { callbackRead = true; true })
            fail("Migration gate must fail closed")
        } catch (_: IllegalStateException) { }
        assertFalse(callbackRead)
        assertEquals(before, preferenceNames)
        assertFalse(base.getDatabasePath(blockedName).exists())
    }

    @Test fun brokenOutboxSchemaDoesNotRollbackValidPinAuthority() {
        prepare()
        android.database.sqlite.SQLiteDatabase.openDatabase(base.getDatabasePath("$prefix.db").path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { broken ->
            broken.execSQL("DROP TABLE diagnostic_outbox")
        }
        assertNotNull(complete())
        assertEquals(ControlRuntimeState.OVERRIDE_PENDING, runtime.read(token, now())?.state)
        val pending = ParentAuthStore.pendingOverride(identity, now())
        assertNotNull(pending)
        ParentAuthStore.initializeForTests(db, 7)
        assertEquals(pending, ParentAuthStore.pendingOverride(identity, now()))
    }

    @Test fun readOnlyPollingDoesNotWriteAuthorityButRealMutationStillPersists() {
        prepare()
        val prefs = db.preferences("parent_auth_runtime")
        val before = prefs.all.toMap()
        var writes = 0
        db.beforeStateWriteForTests = { writes++ }
        repeat(20) {
            atomic {
                assertEquals(ParentAuthStatus.WAITING,
                    ParentAuthStore.status("private-token", identity.packageName, identity.processSessionId, wall()))
                assertNull(ParentAuthStore.getOverride(identity, true, wall(), now()))
                assertNull(ParentAuthStore.pendingOverride(identity, now()))
                assertNotNull(runtime.read(token, now()))
            }
        }
        assertEquals("Read-only polling must issue no state writes", 0, writes)
        assertEquals(before, prefs.all)
        assertNotNull(complete())
        assertTrue("A real mutation must still persist", writes > 0)
        atomic {
            assertTrue(runtime.transition(token, "incident", ControlRuntimeState.OVERRIDE_ACTIVE, now()))
            assertNotNull(ParentAuthStore.activateOverride(identity, true, now(), true))
        }
        val active = prefs.all.toMap()
        writes = 0
        repeat(20) {
            atomic {
                assertNotNull(ParentAuthStore.getOverride(identity, true, wall(), now()))
                assertNotNull(ParentAuthStore.activateOverride(identity, true, now(), true))
                assertNull(ParentAuthStore.pendingOverride(identity, now()))
            }
        }
        assertEquals("Active queries and idempotent activation must not write", 0, writes)
        assertEquals(active, prefs.all)
    }
}
