package com.liuml.apptimelimiter.core

/**
 * LSPosed can invoke load-package callbacks for WebView or another injected package while that
 * code is loaded inside a target app process. Only the package that owns the process may install
 * the Activity lifecycle limiter; otherwise two independent limiters compete in one process.
 */
object HookProcessOwnershipPolicy {
    fun ownsProcess(packageName: String?, processName: String?): Boolean {
        val packageValue = packageName.orEmpty()
        val processValue = processName.orEmpty()
        return packageValue.isNotBlank() &&
            (processValue == packageValue || processValue.startsWith("$packageValue:"))
    }
}
