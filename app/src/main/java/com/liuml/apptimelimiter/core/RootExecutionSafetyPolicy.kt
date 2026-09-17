package com.liuml.apptimelimiter.core

import java.util.concurrent.TimeUnit

/** JVM-only failure boundaries shared by request checks and the bounded root command. */
internal object RootExecutionSafetyPolicy {
    fun validate(check: () -> Boolean): RestrictionExecutionResult? = try {
        if (check()) null else RestrictionExecutionResult.REJECTED
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        RestrictionExecutionResult.FALLBACK_REQUIRED
    } catch (_: Exception) {
        RestrictionExecutionResult.FALLBACK_REQUIRED
    }

    fun execute(
        ledger: RestrictionExecutionLedger,
        key: String,
        action: () -> RestrictionExecutionResult,
    ): RestrictionExecutionResult {
        ledger.begin(key)?.let { return it }
        var result = RestrictionExecutionResult.FALLBACK_REQUIRED
        return try {
            result = action()
            result
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            result
        } catch (_: Exception) {
            result
        } finally {
            ledger.complete(key, result)
        }
    }

    fun runCommand(timeoutMillis: Long, start: () -> Process): Int {
        if (timeoutMillis <= 0 || Thread.currentThread().isInterrupted) return -1
        var process: Process? = null
        return try {
            val child = start()
            process = child
            if (child.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) child.exitValue() else -1
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        } catch (_: Exception) {
            -1
        } finally {
            try {
                process?.destroyForcibly()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // Cleanup failure must not replace the command's failure result.
            }
        }
    }
}
