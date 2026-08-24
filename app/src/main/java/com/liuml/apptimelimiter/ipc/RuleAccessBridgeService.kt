package com.liuml.apptimelimiter.ipc

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.liuml.apptimelimiter.core.PackageNamePolicy
import com.liuml.apptimelimiter.data.RuleRepository

/**
 * Short-lived visibility bridge for scoped Hook processes on Android 11+.
 *
 * It exposes one narrowly scoped Binder transaction and no rule data. The transaction validates
 * the real Binder caller UID, accepts only that caller's configured package, and restores the
 * temporary RuleProvider URI grant that Android discards on reboot.
 */
class RuleAccessBridgeService : Service() {
    private val binder = object : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code != TRANSACTION_ENSURE_ACCESS) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(DESCRIPTOR)
            val packageName = data.readString().orEmpty()
            val callingUid = getCallingUid()
            val result = ensureAccess(packageName, callingUid)
            reply?.writeNoException()
            reply?.writeInt(if (result.first) 1 else 0)
            reply?.writeString(result.second)
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun ensureAccess(packageName: String, callingUid: Int): Pair<Boolean, String> {
        if (!PackageNamePolicy.isValid(packageName) || packageName == applicationContext.packageName) {
            return false to "invalid_package"
        }
        val callerMatches = applicationContext.packageManager
            .getPackagesForUid(callingUid)
            .orEmpty()
            .contains(packageName)
        if (!callerMatches) return false to "caller_mismatch"

        val repository = RuleRepository(applicationContext)
        if (packageName !in repository.configuredPackages()) {
            return false to "rule_not_configured"
        }
        val identity = Binder.clearCallingIdentity()
        val granted = try {
            repository.grantRuleAccess(packageName)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return if (granted) true to "temporary_grant_restored" else false to "grant_failed"
    }

    companion object {
        const val DESCRIPTOR = "com.liuml.apptimelimiter.ipc.RuleAccessBridge"
        const val TRANSACTION_ENSURE_ACCESS = IBinder.FIRST_CALL_TRANSACTION
    }
}
