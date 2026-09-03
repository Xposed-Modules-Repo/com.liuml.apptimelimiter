package com.liuml.apptimelimiter.xposed

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Process
import android.util.Log
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.core.HookProcessOwnershipPolicy
import com.liuml.apptimelimiter.core.LimitEnforcementPolicy
import com.liuml.apptimelimiter.core.ProtectionModePolicy
import com.liuml.apptimelimiter.data.LimitEnforcementMode
import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.data.RuleRepository
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern API entry used by LSPosed 2.x/API 100+.
 *
 * The legacy entry remains packaged for frameworks that do not recognize Modern Xposed metadata.
 * Both entries install the same RuntimeLimiter and differ only in their framework adapters.
 */
class ModernAppTimeLimitHook : XposedModule() {
    private var processName = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val packageName = param.packageName
        if (packageName == MODULE_PACKAGE) return
        val resolvedProcessName = processName.ifBlank { packageName }
        if (!HookProcessOwnershipPolicy.ownsProcess(packageName, resolvedProcessName)) {
            modernLog(
                "HOOK_FOREIGN_PACKAGE_SKIPPED package=$packageName process=$resolvedProcessName",
            )
            return
        }
        if (!ProcessHookInstallationRegistry.claim()) {
            modernLog(
                "HOOK_DUPLICATE_INSTALL_SKIPPED package=$packageName process=$resolvedProcessName",
            )
            return
        }

        val preferences = runCatching {
            ModernRulePreferences(getRemotePreferences(RuleRepository.PREFS_NAME))
        }.getOrElse { error ->
            modernLog("REMOTE_PREFERENCES_UNAVAILABLE package=$packageName", error)
            EmptyRulePreferences
        }
        val initialProtectionMode = ProtectionModePolicy.parse(
            storedValue = preferences.getString(RuleRepository.KEY_PROTECTION_MODE, null),
            legacyNonRootEnabled = preferences.getBoolean(
                RuleRepository.KEY_NON_ROOT_PROTECTION_ENABLED,
                false,
            ),
            legacyShizukuEnabled = preferences.getBoolean(
                RuleRepository.KEY_SHIZUKU_ENHANCEMENT_ENABLED,
                false,
            ),
        )
        val installMediaHooks = initialProtectionMode == ProtectionMode.XPOSED &&
            LimitEnforcementPolicy.shouldInstallMediaHooks(
                LimitEnforcementPolicy.parseMode(
                    preferences.getString(RuleRepository.KEY_LIMIT_ENFORCEMENT_MODE, null),
                ),
            )
        val logger = HookLogger { message, error -> modernLog(message, error) }
        val mediaPauseController = MediaPauseController(
            classLoader = param.classLoader,
            constructorHookInstaller = ConstructorHookInstaller { targetClass, onCreated ->
                var installed = false
                targetClass.declaredConstructors.forEach { constructor ->
                    runCatching {
                        hook(constructor)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val result = chain.proceed()
                                onCreated(chain.thisObject)
                                result
                            }
                        installed = true
                    }.onFailure { error ->
                        modernLog(
                            "MEDIA_CONSTRUCTOR_HOOK_FAILED class=${targetClass.name}",
                            error,
                        )
                    }
                }
                installed
            },
        )
        val limiter = RuntimeLimiter(
            packageName = packageName,
            processName = resolvedProcessName,
            preferences = preferences,
            mediaPauseController = mediaPauseController,
            logger = logger,
        )
        val installedEntries = mutableListOf<String>()
        val installErrors = mutableListOf<String>()

        runCatching {
            hook(
                Instrumentation::class.java.getDeclaredMethod(
                    "callActivityOnResume",
                    Activity::class.java,
                ),
            ).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.args.firstOrNull() as? Activity)?.let(limiter::onActivityResumed)
                    result
                }
            hook(
                Instrumentation::class.java.getDeclaredMethod(
                    "callActivityOnPause",
                    Activity::class.java,
                ),
            ).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.args.firstOrNull() as? Activity)?.let(limiter::onActivityPaused)
                    result
                }
            installedEntries += "Instrumentation"
        }.onFailure { error ->
            installErrors += "Instrumentation:${error.javaClass.simpleName}"
            modernLog("HOOK_ENTRY_FAILED entry=Instrumentation package=$packageName", error)
        }

        if (installMediaHooks) {
            runCatching {
                val mediaEntries = mediaPauseController.installHooks()
                if (mediaEntries.isNotEmpty()) {
                    installedEntries += "MediaPause(${mediaEntries.distinct().joinToString("+")})"
                }
            }.onFailure { error ->
                installErrors += "MediaPause:${error.javaClass.simpleName}"
                modernLog("MEDIA_HOOK_FAILED package=$packageName", error)
            }
        }

        runCatching {
            hook(Activity::class.java.getDeclaredMethod("onResume"))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? Activity)?.let(limiter::onActivityResumedFallback)
                    result
                }
            hook(Activity::class.java.getDeclaredMethod("onPause"))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? Activity)?.let(limiter::onActivityPausedFallback)
                    result
                }
            installedEntries += "ActivityFallback"
        }.onFailure { error ->
            installErrors += "ActivityFallback:${error.javaClass.simpleName}"
            modernLog("HOOK_ENTRY_FAILED entry=ActivityFallback package=$packageName", error)
        }

        val processBits = if (Process.is64Bit()) 64 else 32
        modernLog(
            "HOOK_${if (installedEntries.isEmpty()) "FAILED" else "INSTALLED"} " +
                "package=$packageName process=$resolvedProcessName bits=$processBits " +
                "abi=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()} " +
                "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "api=modern entries=${installedEntries.joinToString("+")} " +
                "errors=${installErrors.joinToString(",").ifBlank { "none" }}",
        )
    }

    private fun modernLog(message: String, error: Throwable? = null) {
        val fullMessage = if (message.startsWith("AppTimeLimiter:")) {
            message
        } else {
            "AppTimeLimiter: $message"
        }
        log(Log.INFO, "AppTimeLimiter", fullMessage, error)
    }

    private companion object {
        const val MODULE_PACKAGE = "com.liuml.apptimelimiter"
    }
}
