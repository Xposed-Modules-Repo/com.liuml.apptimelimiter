package com.liuml.apptimelimiter.update

/** Auto/manual checks subscribe to one in-flight operation; completion releases it for retry. */
internal class SingleFlightCallbacks<T> {
    private val callbacks = mutableListOf<(T) -> Unit>()
    @Synchronized fun join(callback: (T) -> Unit): Boolean {
        callbacks.add(callback)
        return callbacks.size == 1
    }
    @Synchronized fun isRunning(): Boolean = callbacks.isNotEmpty()
    @Synchronized fun drain(): List<(T) -> Unit> = callbacks.toList().also { callbacks.clear() }
}
