package com.liuml.apptimelimiter.xposedstatus

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.migration.MigrationStorageGate
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface XposedServiceAdapter {
    fun snapshot(): XposedFrameworkSnapshot

    fun requestScope(
        packages: List<String>,
        onApproved: (List<String>) -> Unit,
        onFailure: (String) -> Unit,
    )

    fun removeScope(packages: List<String>)

    fun replaceRemotePreferences(group: String, values: Map<String, *>)
}

private class LibXposedServiceAdapter(
    private val service: XposedService,
) : XposedServiceAdapter {
    override fun snapshot(): XposedFrameworkSnapshot {
        val apiVersion = service.apiVersion
        return XposedFrameworkSnapshot(
            connected = true,
            frameworkName = service.frameworkName,
            frameworkVersion = service.frameworkVersion,
            apiVersion = apiVersion,
            scopePackages = service.scope.toSet(),
            runningTargets = if (apiVersion >= XposedService.API_102) {
                service.runningTargets.map { target ->
                    XposedRunningTarget(
                        processName = target.processName,
                        loadedVersionCode = target.loadedVersionCode,
                        state = runCatching {
                            HookTargetState.valueOf(target.state.name)
                        }.getOrDefault(HookTargetState.UNKNOWN),
                    )
                }
            } else {
                emptyList()
            },
            capturedAtMillis = System.currentTimeMillis(),
        )
    }

    override fun requestScope(
        packages: List<String>,
        onApproved: (List<String>) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        service.requestScope(
            packages,
            object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    onApproved(approved)
                }

                override fun onScopeRequestFailed(message: String) {
                    onFailure(message)
                }
            },
        )
    }

    override fun removeScope(packages: List<String>) {
        service.removeScope(packages)
    }

    override fun replaceRemotePreferences(group: String, values: Map<String, *>) {
        val editor = service.getRemotePreferences(group).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> editor.remove(key)
            }
        }
        check(editor.commit()) { "Remote preferences commit failed" }
    }
}

class XposedStatusRepository private constructor() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xposed-status").apply { isDaemon = true }
    }
    private val registered = AtomicBoolean(false)
    private val _snapshot = MutableStateFlow(XposedFrameworkSnapshot())
    @Volatile
    private var serviceAdapter: XposedServiceAdapter? = null
    @Volatile
    private var boundService: XposedService? = null
    @Volatile
    private var lastSuccessfulSnapshot: XposedFrameworkSnapshot? = null
    @Volatile
    private var diagnosticsRepository: DiagnosticsRepository? = null
    @Volatile
    private var appContext: Context? = null

    val snapshot: StateFlow<XposedFrameworkSnapshot> = _snapshot.asStateFlow()

    fun initialize(context: Context? = null) {
        context?.applicationContext?.let { appContext ->
            this.appContext = appContext
            if (diagnosticsRepository == null) {
                diagnosticsRepository = DiagnosticsRepository(appContext)
            }
        }
        if (!registered.compareAndSet(false, true)) return
        runCatching {
            appendDiagnostic(
                event = "XPOSED_SERVICE_LISTENER_REGISTERED",
                message = "thread=${Thread.currentThread().name}",
            )
            XposedServiceHelper.registerListener(
                object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        boundService = service
                        val adapter = LibXposedServiceAdapter(service)
                        serviceAdapter = adapter
                        appendDiagnostic(
                            event = "XPOSED_SERVICE_BOUND",
                            message = "api=${service.apiVersion} framework=${service.frameworkName.take(80)}",
                        )
                        syncRulePreferences()
                        refresh()
                    }

                    override fun onServiceDied(service: XposedService) {
                        if (boundService !== service) return
                        boundService = null
                        serviceAdapter = null
                        appendDiagnostic(
                            level = "WARN",
                            event = "XPOSED_SERVICE_DIED",
                            message = "last_snapshot=${lastSuccessfulSnapshot?.capturedAtMillis ?: 0L}",
                        )
                        _snapshot.value = lastSuccessfulSnapshot?.copy(
                            connected = false,
                            stale = true,
                            errorMessage = "Xposed service disconnected",
                        ) ?: XposedFrameworkSnapshot(
                            stale = true,
                            errorMessage = "Xposed service disconnected",
                        )
                    }
                },
            )
        }.onFailure { error ->
            registered.set(false)
            appendDiagnostic(
                level = "ERROR",
                event = "XPOSED_SERVICE_LISTENER_REGISTRATION_FAILED",
                message = errorSummary(error),
            )
            _snapshot.value = lastSuccessfulSnapshot?.copy(
                connected = false,
                stale = true,
                errorMessage = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            ) ?: XposedFrameworkSnapshot(
                stale = true,
                errorMessage = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }
    }

    fun refresh() {
        val adapter = serviceAdapter ?: return
        serviceExecutor.execute {
            val refreshed = runCatching(adapter::snapshot).fold(
                onSuccess = { snapshot ->
                    lastSuccessfulSnapshot = snapshot
                    appendDiagnostic(
                        event = "XPOSED_SCOPE_REFRESHED",
                        stateSignature = "${snapshot.apiVersion}:${snapshot.scopePackages.sorted().joinToString(",")}",
                        message = "api=${snapshot.apiVersion} scope=${snapshot.scopePackages.sorted().take(MAX_DIAGNOSTIC_PACKAGES).joinToString(",")} running=${snapshot.runningTargets.size}",
                    )
                    snapshot
                },
                onFailure = { error ->
                    appendDiagnostic(
                        level = "WARN",
                        event = "XPOSED_SCOPE_REFRESH_FAILED",
                        message = errorSummary(error),
                    )
                    lastSuccessfulSnapshot?.copy(
                        connected = false,
                        stale = true,
                        errorMessage =
                            "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                    ) ?: XposedFrameworkSnapshot(
                        stale = true,
                        errorMessage =
                            "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                    )
                },
            )
            if (serviceAdapter === adapter) {
                _snapshot.value = refreshed
            }
        }
    }

    fun syncRulePreferences() {
        val adapter = serviceAdapter ?: return
        val context = appContext ?: return
        if (!MigrationStorageGate.maySynchronizeMirror(context)) {
            appendDiagnostic(
                level = "WARN",
                event = "XPOSED_REMOTE_RULES_SYNC_DEFERRED",
                message = "migration_not_ready",
            )
            return
        }
        // Modern remote preferences and the legacy redirected `rules` XML are distinct stores.
        // Always build the remote snapshot from the private authoritative repository.
        val snapshot = RuleRepository(context).rawAuthoritativeSnapshot()
        serviceExecutor.execute {
            runCatching {
                adapter.replaceRemotePreferences(
                    RuleRepository.PREFS_NAME,
                    snapshot,
                )
            }.onSuccess {
                appendDiagnostic(
                    event = "XPOSED_REMOTE_RULES_SYNCED",
                    stateSignature = "${snapshot.size}:${snapshot[RuleRepository.KEY_RULESET_GENERATION]}",
                    message = "entries=${snapshot.size}",
                )
            }.onFailure { error ->
                appendDiagnostic(
                    level = "ERROR",
                    event = "XPOSED_REMOTE_RULES_SYNC_FAILED",
                    message = errorSummary(error),
                )
            }
        }
    }

    fun requestScope(
        packages: Collection<String>,
        callback: (approved: Set<String>, errorMessage: String?) -> Unit,
    ) {
        val requested = packages.filter(String::isNotBlank).distinct()
        val adapter = serviceAdapter
        if (adapter == null || requested.isEmpty()) {
            callback(emptySet(), "当前框架不支持直接申请作用域")
            return
        }
        serviceExecutor.execute {
            runCatching {
                adapter.requestScope(
                    requested,
                    onApproved = { approved ->
                        mainHandler.post {
                            appendDiagnostic(
                                event = "XPOSED_SCOPE_REQUEST_APPROVED",
                                message = "requested=${requested.size} approved=${approved.sorted().take(MAX_DIAGNOSTIC_PACKAGES).joinToString(",")}",
                            )
                            refresh()
                            callback(approved.toSet(), null)
                        }
                    },
                    onFailure = { message ->
                        mainHandler.post {
                            appendDiagnostic(
                                level = "WARN",
                                event = "XPOSED_SCOPE_REQUEST_FAILED",
                                message = "requested=${requested.size} error=${message.take(300)}",
                            )
                            refresh()
                            callback(emptySet(), message.ifBlank { "作用域申请失败" })
                        }
                    },
                )
            }.onFailure { error ->
                mainHandler.post {
                    appendDiagnostic(
                        level = "ERROR",
                        event = "XPOSED_SCOPE_REQUEST_FAILED",
                        message = "requested=${requested.size} error=${errorSummary(error)}",
                    )
                    callback(
                        emptySet(),
                        "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                    )
                }
            }
        }
    }

    fun removeScope(
        packages: Collection<String>,
        callback: (errorMessage: String?) -> Unit,
    ) {
        val requested = packages.filter(String::isNotBlank).distinct()
        val adapter = serviceAdapter
        if (adapter == null || requested.isEmpty()) {
            callback(if (requested.isEmpty()) null else "当前框架不支持直接移除作用域")
            return
        }
        serviceExecutor.execute {
            runCatching { adapter.removeScope(requested) }
                .onSuccess {
                    mainHandler.post {
                        appendDiagnostic(
                            event = "XPOSED_SCOPE_REMOVE_COMPLETED",
                            message = "removed=${requested.sorted().take(MAX_DIAGNOSTIC_PACKAGES).joinToString(",")}",
                        )
                        refresh()
                        callback(null)
                    }
                }
                .onFailure { error ->
                    mainHandler.post {
                        appendDiagnostic(
                            level = "ERROR",
                            event = "XPOSED_SCOPE_REMOVE_FAILED",
                            message = "requested=${requested.size} error=${errorSummary(error)}",
                        )
                        callback(errorSummary(error))
                    }
                }
        }
    }

    private fun appendDiagnostic(
        level: String = "INFO",
        event: String,
        stateSignature: String = event,
        message: String,
    ) {
        diagnosticsRepository?.appendRateLimited(
            level = level,
            packageName = MODULE_PACKAGE_NAME,
            event = event,
            stateSignature = stateSignature.take(500),
            message = message.take(1_000),
        )
    }

    private fun errorSummary(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(500)

    companion object {
        private const val MODULE_PACKAGE_NAME = "com.liuml.apptimelimiter"
        private const val MAX_DIAGNOSTIC_PACKAGES = 20

        val instance: XposedStatusRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            XposedStatusRepository()
        }
    }
}
