package com.liuml.apptimelimiter.nonroot

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.core.PackageNamePolicy

/** Used only in the existing Shizuku process or a bounded Root app_process command. */
@SuppressLint("SoonBlockedPrivateApi")
object PrivilegedTaskAccess {
    @Volatile private var suppliedContext: Context? = null
    fun initialize(context: Context) { suppliedContext = context.applicationContext ?: context }
    private val systemContext: Context by lazy {
        val thread = Class.forName("android.app.ActivityThread")
        thread.getMethod("getSystemContext").invoke(thread.getMethod("systemMain").invoke(null)) as Context
    }
    private val context: Context get() = suppliedContext ?: systemContext

    fun isManagerCaller(uid: Int): Boolean = runCatching {
        context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid == uid
    }.getOrDefault(false)

    private fun service(): Any {
        val modern = Build.VERSION.SDK_INT >= 29
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, if (modern) "activity_task" else "activity") as IBinder
        return checkNotNull(Class.forName(if (modern) "android.app.IActivityTaskManager\$Stub" else "android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder))
    }

    private fun tasks(api: Any): List<ActivityManager.RunningTaskInfo> {
        val method = api.javaClass.methods.filter { it.name == "getTasks" }
            .sortedBy { it.parameterCount }.firstOrNull {
                it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType &&
                    it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType || type == Boolean::class.javaPrimitiveType }
            } ?: error("unsupported_tasks")
        method.isAccessible = true // Framework Binder proxy classes may be non-public.
        val args: Array<Any> = method.parameterTypes.mapIndexed<Class<*>, Any> { index, type ->
            if (type == Boolean::class.javaPrimitiveType) false else if (index == 0) 100 else -1
        }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        return method.invoke(api, *args) as List<ActivityManager.RunningTaskInfo>
    }

    @Suppress("DEPRECATION")
    private fun id(task: ActivityManager.RunningTaskInfo) = if (Build.VERSION.SDK_INT >= 29) task.taskId else task.id
    private fun user(task: ActivityManager.RunningTaskInfo) = task.javaClass.getField("userId").getInt(task)
    private fun mode(task: ActivityManager.RunningTaskInfo): Int = runCatching {
        val config = task.javaClass.getField("configuration").get(task)
        val window = config.javaClass.getField("windowConfiguration").get(config)
        window.javaClass.getMethod("getWindowingMode").invoke(window) as Int
    }.getOrDefault(0)

    private fun allowed(packageName: String, userId: Int): Boolean {
        if (!PackageNamePolicy.isValid(packageName) || userId < 0 || packageName == BuildConfig.APPLICATION_ID) return false
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        if (info.uid / 100000 != userId || info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return false
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return packageName != home && packageName !in setOf("android", "com.android.systemui", "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.permissioncontroller")
    }

    fun inspect(packageName: String, userId: Int): String? = runCatching {
        if (!allowed(packageName, userId)) return null
        val task = tasks(service()).filter { it.baseActivity?.packageName == packageName && user(it) == userId }.singleOrNull() ?: return null
        val bounds = runCatching {
            val config = task.javaClass.getField("configuration").get(task)
            val window = config.javaClass.getField("windowConfiguration").get(config)
            window.javaClass.getMethod("getBounds").invoke(window) as android.graphics.Rect
        }.getOrNull()
        "${id(task)}|${mode(task)}" + if (bounds != null && !bounds.isEmpty)
            "|${bounds.left}|${bounds.top}|${bounds.right}|${bounds.bottom}" else ""
    }.getOrNull()

    fun remove(packageName: String, userId: Int, taskId: Int, deadlineElapsed: Long): Boolean = runCatching {
        if (taskId < 0 || !allowed(packageName, userId)) return false
        val api = service()
        val task = tasks(api).singleOrNull { id(it) == taskId }
        if (task == null) return true // A retry after a successful removal is harmless.
        if (task.baseActivity?.packageName != packageName || user(task) != userId) return false
        val method = api.javaClass.methods.singleOrNull { it.name == "removeTask" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) } ?: return false
        method.isAccessible = true
        if (android.os.SystemClock.elapsedRealtime() > deadlineElapsed) return false
        method.invoke(api, taskId) == true && tasks(api).none { id(it) == taskId }
    }.getOrDefault(false)

    @JvmStatic fun main(args: Array<String>) {
        // Root app_process invokes this main directly rather than Android Application main.
        @Suppress("DEPRECATION")
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
        val result = runCatching {
            if (args.lastOrNull()?.toLongOrNull()?.let { android.os.SystemClock.elapsedRealtime() <= it } != true) return@runCatching null
            when {
                args.size == 4 && args[0] == "inspect" -> inspect(args[1], args[2].toInt())
                args.size == 5 && args[0] == "remove" -> if (remove(args[1], args[2].toInt(), args[3].toInt(), args[4].toLong())) "closed" else null
                else -> null
            }
        }.getOrNull()
        if (result != null) println("TASK:$result")
    }
}
