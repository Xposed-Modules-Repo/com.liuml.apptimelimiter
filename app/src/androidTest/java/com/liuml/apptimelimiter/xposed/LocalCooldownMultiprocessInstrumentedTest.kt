package com.liuml.apptimelimiter.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LocalCooldownMultiprocessInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private inner class Worker(service: String) : AutoCloseable {
        private val context = instrumentation.targetContext
        private val connected = LinkedBlockingQueue<Messenger>()
        private val responses = LinkedBlockingQueue<Bundle>()
        private val thread = HandlerThread("cooldown-test-replies").apply { start() }
        private val reply = Messenger(Handler(thread.looper) {
            responses.put(it.data); true
        })
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.put(Messenger(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        private var bound = false
        private var endpoint: Messenger? = null
        init {
            try {
                val intent = Intent().setComponent(ComponentName(instrumentation.context.packageName, service))
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bound) { "Cannot bind test worker $service" }
                endpoint = checkNotNull(connected.poll(15, TimeUnit.SECONDS)) { "Test worker bind timeout" }
            } catch (failure: Throwable) { close(); throw failure }
        }
        fun send(request: Bundle) {
            endpoint!!.send(Message.obtain().apply { data = request; replyTo = reply })
        }
        fun receive(): Bundle = checkNotNull(responses.poll(20, TimeUnit.SECONDS)) { "Worker response timeout" }.also {
            assertTrue(it.getString("error"), it.getBoolean("ok"))
        }
        override fun close() {
            if (bound) { context.unbindService(connection); bound = false }
            thread.quitSafely()
        }
    }

    @Test fun twoRealProcessesShareOneClaimAndPersistTheSameDeadline() {
        val name = "cooldown_mp_${UUID.randomUUID()}"
        Worker("com.liuml.apptimelimiter.xposed.CooldownTestService").use { local ->
            Worker("com.liuml.apptimelimiter.xposed.CooldownRemoteTestService").use { remote ->
                try {
                    val prepare = Bundle().apply { putString("name", name); putBoolean("prepare", true) }
                    local.send(prepare)
                    remote.send(prepare)
                    local.receive()
                    remote.receive()
                    val request = Bundle().apply {
                        putString("name", name)
                        putLong("startElapsed", SystemClock.elapsedRealtime() + 1500)
                        putLong("occurredAt", System.currentTimeMillis())
                    }
                    local.send(request)
                    remote.send(request)
                    val first = local.receive()
                    val second = remote.receive()
                    assertNotEquals(first.getInt("pid"), second.getInt("pid"))
                    assertNotEquals(Process.myPid(), first.getInt("pid"))
                    assertEquals(first.getInt("uid"), second.getInt("uid"))
                    assertEquals(1, listOf(first, second).count { it.getBoolean("new") })
                    assertEquals(1, listOf(first, second).count { it.getBoolean("started") })
                    assertTrue(first.getLong("endElapsed") > SystemClock.elapsedRealtime())
                    assertEquals(first.getLong("endWall"), second.getLong("endWall"))
                    assertEquals(first.getLong("endElapsed"), second.getLong("endElapsed"))
                    request.putLong("startElapsed", 0)
                    local.send(request)
                    remote.send(request)
                    listOf(local.receive(), remote.receive()).forEach {
                        assertFalse(it.getBoolean("new"))
                        assertFalse(it.getBoolean("started"))
                        assertEquals(first.getLong("endElapsed"), it.getLong("endElapsed"))
                        assertEquals(first.getLong("endWall"), it.getLong("endWall"))
                    }
                } finally {
                    local.send(Bundle().apply { putString("name", name); putBoolean("cleanup", true) })
                    local.receive()
                }
            }
        }
    }
}
