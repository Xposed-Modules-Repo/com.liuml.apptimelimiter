package com.liuml.apptimelimiter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.liuml.apptimelimiter.core.ParentAuthBootstrapPolicy
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository

/**
 * User-initiated, transparent provider-access bootstrap.
 *
 * Some OEM systems reject cold-starting an exported provider or bound service from a target app.
 * Starting this Activity for result is tied to the user's PIN tap, so Android may foreground the
 * Time Stop process. The system-owned callingPackage is then checked before restoring only that
 * configured package's temporary provider grant.
 */
class ParentAuthBootstrapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val requestedPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        val caller = callingPackage
        val repository = RuleRepository(this)
        val allowed = ParentAuthBootstrapPolicy.isAllowed(
            callingPackage = caller,
            requestedPackage = requestedPackage,
            ownPackage = packageName,
            configuredPackages = repository.configuredPackages(),
        )
        val granted = allowed && repository.grantRuleAccess(requestedPackage.orEmpty())
        val event = when {
            !allowed -> "RULE_PROVIDER_FOREGROUND_BOOTSTRAP_REJECTED"
            granted -> "RULE_PROVIDER_FOREGROUND_BOOTSTRAP_SUCCEEDED"
            else -> "RULE_PROVIDER_FOREGROUND_BOOTSTRAP_FAILED"
        }
        DiagnosticsRepository(this).append(
            level = if (granted) "INFO" else "WARN",
            packageName = requestedPackage.orEmpty().take(160).ifBlank { packageName },
            event = event,
            message = "callerMatches=${caller == requestedPackage}, configured=$allowed, granted=$granted",
        )
        setResult(
            if (granted) RESULT_OK else RESULT_CANCELED,
            Intent().putExtra(EXTRA_ACCESS_GRANTED, granted),
        )
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_ACCESS_GRANTED = "access_granted"
    }
}
