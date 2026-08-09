package com.liuml.apptimelimiter.nonroot

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import androidx.annotation.Keep
import rikka.shizuku.SystemServiceHelper

class ShizukuExecutionUserService() : IShizukuExecutionService.Stub() {
    @Suppress("UNUSED_PARAMETER")
    @Keep
    constructor(context: Context) : this()

    // Shizuku starts UserService with app_process, where non-SDK API restrictions are disabled.
    // Keep this suppression local; ordinary app-process code must not call this API.
    @SuppressLint("SoonBlockedPrivateApi")
    override fun forceStopPackage(packageName: String?, userId: Int): Int {
        val target = packageName.orEmpty()
        if (!PACKAGE_NAME.matches(target) || userId < 0) return RESULT_REJECTED
        return runCatching {
            val binder = SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)
                ?: return RESULT_UNSUPPORTED
            val stub = Class.forName("android.app.IActivityManager\$Stub")
            val service = stub.getDeclaredMethod(
                "asInterface",
                IBinder::class.java,
            ).invoke(null, binder) ?: return RESULT_UNSUPPORTED
            val method = service.javaClass.methods.firstOrNull {
                it.name == "forceStopPackage" &&
                    it.parameterTypes.contentEquals(
                        arrayOf(String::class.java, Int::class.javaPrimitiveType),
                    )
            } ?: return RESULT_UNSUPPORTED
            method.invoke(service, target, userId)
            RESULT_OK
        }.getOrDefault(RESULT_FAILED)
    }

    override fun destroy() {
        System.exit(0)
    }

    companion object {
        const val RESULT_OK = 0
        const val RESULT_REJECTED = 1
        const val RESULT_UNSUPPORTED = 2
        const val RESULT_FAILED = 3
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
