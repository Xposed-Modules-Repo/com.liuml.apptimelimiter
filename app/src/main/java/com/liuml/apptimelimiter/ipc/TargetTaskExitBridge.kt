package com.liuml.apptimelimiter.ipc

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.*
import com.liuml.apptimelimiter.core.TargetTaskExitPolicy
import com.liuml.apptimelimiter.core.TargetWindowMode

/** A capability for one original task. The manager cannot supply another task or a command. */
class TargetTaskExitBridge(
    activity: Activity,
    private val managerUid: Int,
    private val stillCurrent: () -> Boolean,
) : Binder() {
    private val context = activity.applicationContext
    val boundTaskId = activity.taskId
    val windowMode: TargetWindowMode = activityMode(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var closing = false

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != CLOSE || Binder.getCallingUid() != managerUid) return false
        data.enforceInterface(DESCRIPTOR)
        val receiver = ResultReceiver.CREATOR.createFromParcel(data)
        handler.post {
            if (!stillCurrent()) { receiver.send(FAILED, null); return@post }
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: run { receiver.send(FAILED, null); return@post }
            val task = runCatching { manager.appTasks.singleOrNull { taskId(it.taskInfo) == boundTaskId } }
                .getOrElse { receiver.send(FAILED, null); return@post }
            if (task == null) { receiver.send(CLOSED, null); return@post }
            val info = runCatching { task.taskInfo }.getOrNull()
            // A target at the top does not prove ownership of the task below it.
            val owner = info?.baseActivity?.packageName
            if (info == null || !TargetTaskExitPolicy.matches(context.packageName, boundTaskId,
                    Process.myUid() / 100000, owner, taskId(info), Process.myUid() / 100000)) {
                receiver.send(FAILED, null); return@post
            }
            if (!closing) {
                closing = true
                if (runCatching { task.finishAndRemoveTask() }.isFailure) {
                    closing = false; receiver.send(FAILED, null); return@post
                }
            }
            fun confirm(attempt: Int) {
                val remaining = runCatching { manager.appTasks.any { taskId(it.taskInfo) == boundTaskId } }.getOrNull()
                when {
                    remaining == false -> receiver.send(CLOSED, null)
                    attempt >= 10 || remaining == null -> { closing = false; receiver.send(FAILED, null) }
                    else -> handler.postDelayed({ confirm(attempt + 1) }, 100L)
                }
            }
            confirm(0)
        }
        return true
    }

    companion object {
        const val EXTRA = "target_task_exit_bridge"
        const val MODE = "target_window_mode"
        const val TASK_ID = "target_task_id"
        const val CLOSED = 1
        const val FAILED = 0
        private const val CLOSE = IBinder.FIRST_CALL_TRANSACTION
        private const val DESCRIPTOR = "com.liuml.apptimelimiter.TargetTaskExit"

        /** Manager-owned capability: the caller cannot choose a package, user or task. */
        fun managerBridge(executor: java.util.concurrent.Executor, stillCurrent: () -> Boolean,
            close: () -> Boolean): IBinder = object : Binder() {
            private val main = Handler(Looper.getMainLooper())
            private var inFlight = false
            private var closed = false
            private val receivers = mutableListOf<ResultReceiver>()
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != CLOSE || Binder.getCallingUid() != Process.myUid()) return false
                data.enforceInterface(DESCRIPTOR)
                val receiver = ResultReceiver.CREATOR.createFromParcel(data)
                main.post {
                    if (closed) { receiver.send(CLOSED, null); return@post }
                    if (!stillCurrent() || receivers.size >= 8) { receiver.send(FAILED, null); return@post }
                    receivers.add(receiver)
                    if (inFlight) return@post
                    inFlight = true
                    val latestStart = SystemClock.elapsedRealtime() + 2_000L
                    runCatching { executor.execute {
                        // Do not start a late Root/Shizuku action after the page timed out.
                        val result = SystemClock.elapsedRealtime() <= latestStart && runCatching(close).getOrDefault(false)
                        main.post {
                            inFlight = false
                            closed = result
                            // Task removal itself changes foreground state. Report confirmed
                            // closure even then; the page's generation guards stale callbacks.
                            receivers.toList().forEach { it.send(if (result) CLOSED else FAILED, null) }
                            receivers.clear()
                        }
                    } }.onFailure {
                        inFlight = false
                        receivers.toList().forEach { it.send(FAILED, null) }
                        receivers.clear()
                    }
                }
                return true
            }
        }

        fun transport(source: Bundle?): Bundle = runCatching { Bundle().apply {
            putBinder(EXTRA, source?.getBinder(EXTRA))
            putString(MODE, source?.getString(MODE))
            putInt(TASK_ID, source?.getInt(TASK_ID, -1) ?: -1)
        } }.getOrDefault(Bundle())
        @Suppress("DEPRECATION")
        private fun taskId(info: ActivityManager.RecentTaskInfo?): Int {
            checkNotNull(info) { "task_info_unavailable" }
            return if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id
        }

        fun request(binder: IBinder, receiver: ResultReceiver): Boolean {
            val data = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                // Marshal the framework receiver directly, never its app-specific subclass name.
                receiver.writeToParcel(data, 0)
                binder.transact(CLOSE, data, null, IBinder.FLAG_ONEWAY)
            } catch (_: Exception) { false } finally { data.recycle() }
        }

        fun request(binder: IBinder, handler: Handler, result: (Int) -> Unit): Boolean =
            request(binder, object : ResultReceiver(handler) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { result(resultCode) }
            })

        fun activityMode(activity: Activity): TargetWindowMode {
            val multi = activity.isInMultiWindowMode || activity.isInPictureInPictureMode
            val mode = runCatching {
                val field = android.content.res.Configuration::class.java.getField("windowConfiguration")
                val window = field.get(activity.resources.configuration)
                window.javaClass.getMethod("getWindowingMode").invoke(window) as Int
            }.getOrDefault(0)
            // Some OEMs expose floating mode in Configuration but restrict the hidden getter.
            // This fallback only selects an in-window panel; it never authorizes global Home.
            val windowedConfiguration = mode == 0 && com.liuml.apptimelimiter.core.WindowRestrictionPolicy
                .configurationIsWindowed(activity.resources.configuration.toString())
            return TargetTaskExitPolicy.mode(mode, multi || windowedConfiguration)
        }
    }
}
