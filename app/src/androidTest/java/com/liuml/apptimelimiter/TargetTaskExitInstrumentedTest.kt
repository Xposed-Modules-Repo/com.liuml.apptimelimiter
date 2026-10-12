package com.liuml.apptimelimiter

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import com.liuml.apptimelimiter.ipc.TargetTaskExitBridge
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TargetTaskExitInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun managerRetriesShareOneActionAndRetainCompletedReceipt() {
        val work = LinkedBlockingQueue<Runnable>()
        val replies = LinkedBlockingQueue<Int>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val bridge = TargetTaskExitBridge.managerBridge(java.util.concurrent.Executor { work.offer(it) },
            { true }, { calls.incrementAndGet(); true })
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { replies.offer(resultCode) }
        }
        repeat(2) { assertTrue(TargetTaskExitBridge.request(bridge, receiver)) }
        checkNotNull(work.poll(5, TimeUnit.SECONDS)).run()
        repeat(2) { assertEquals(TargetTaskExitBridge.CLOSED, replies.poll(5, TimeUnit.SECONDS)) }
        assertEquals(1, calls.get())
        assertTrue(TargetTaskExitBridge.request(bridge, receiver))
        assertEquals(TargetTaskExitBridge.CLOSED, replies.poll(5, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
    }

    private fun launch(current: Boolean = true, embedded: Boolean = false): Bundle {
        val queue = LinkedBlockingQueue<Bundle>()
        val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { resultData?.let(queue::offer) }
        }
        context.startActivity(Intent().setClassName(context.packageName, "${context.packageName}.TaskExitTestActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra("reply", frameworkReceiver(reply)).putExtra("current", current).putExtra("embedded", embedded))
        return checkNotNull(queue.poll(10, TimeUnit.SECONDS)) { "Task fixture did not launch" }
    }

    private fun frameworkReceiver(receiver: ResultReceiver): ResultReceiver {
        val parcel = Parcel.obtain()
        return try {
            receiver.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun taskId(info: ActivityManager.RecentTaskInfo?) =
        if (Build.VERSION.SDK_INT >= 29) info?.taskId else info?.id

    @Test fun embeddedRestrictionUsesOriginalWindowWithoutAnotherTask() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = manager.appTasks.map { taskId(it.taskInfo) }.toSet()
        val fixture = launch(embedded = true)
        try {
            assertTrue(fixture.getBoolean("embedded"))
            val added = manager.appTasks.map { taskId(it.taskInfo) }.filterNot { it in before }
            assertEquals(listOf(fixture.getInt("task")), added)
            assertNotEquals(Process.myPid(), fixture.getInt("pid"))
        } finally {
            manager.appTasks.filter { taskId(it.taskInfo) == fixture.getInt("task") }.forEach { it.finishAndRemoveTask() }
        }
    }

    @Test fun remoteTaskExitLeavesUnderlyingTaskAndRetryIsIdempotent() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        context.startActivity(Intent().setClassName(context.packageName, "${context.packageName}.TaskExitUnderlyingTestActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val fixture = launch()
        assertNotEquals(Process.myPid(), fixture.getInt("pid"))
        val underlying = manager.appTasks.single { it.taskInfo?.baseActivity?.className?.endsWith("TaskExitUnderlyingTestActivity") == true }
        val underlyingId = taskId(underlying.taskInfo)
        try {
            val queue = LinkedBlockingQueue<Int>()
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { queue.offer(resultCode) }
            }
            repeat(2) {
                assertTrue(TargetTaskExitBridge.request(checkNotNull(fixture.getBinder("bridge")), receiver))
                assertEquals(TargetTaskExitBridge.CLOSED, queue.poll(5, TimeUnit.SECONDS))
                assertTrue(manager.appTasks.any { taskId(it.taskInfo) == underlyingId })
                assertFalse(manager.appTasks.any { taskId(it.taskInfo) == fixture.getInt("task") })
            }
        } finally {
            manager.appTasks.filter { taskId(it.taskInfo) in setOf(underlyingId, fixture.getInt("task")) }.forEach { it.finishAndRemoveTask() }
        }
    }

    @Test fun staleCallbackCannotRemoveTask() {
        val fixture = launch(current = false)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            val queue = LinkedBlockingQueue<Int>()
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { queue.offer(resultCode) }
            }
            assertTrue(TargetTaskExitBridge.request(checkNotNull(fixture.getBinder("bridge")), receiver))
            assertEquals(TargetTaskExitBridge.FAILED, queue.poll(5, TimeUnit.SECONDS))
            assertTrue(manager.appTasks.any { taskId(it.taskInfo) == fixture.getInt("task") })
        } finally { manager.appTasks.filter { taskId(it.taskInfo) == fixture.getInt("task") }.forEach { it.finishAndRemoveTask() } }
    }
}
