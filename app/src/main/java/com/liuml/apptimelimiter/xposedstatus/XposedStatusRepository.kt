package com.liuml.apptimelimiter.xposedstatus

import android.os.Handler
import android.os.Looper
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

    val snapshot: StateFlow<XposedFrameworkSnapshot> = _snapshot.asStateFlow()

    fun initialize() {
        if (!registered.compareAndSet(false, true)) return
        runCatching {
            XposedServiceHelper.registerListener(
                object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        boundService = service
                        serviceAdapter = LibXposedServiceAdapter(service)
                        refresh()
                    }

                    override fun onServiceDied(service: XposedService) {
                        if (boundService !== service) return
                        boundService = null
                        serviceAdapter = null
                        _snapshot.value = XposedFrameworkSnapshot(
                            errorMessage = "Xposed service disconnected",
                        )
                    }
                },
            )
        }.onFailure { error ->
            _snapshot.value = XposedFrameworkSnapshot(
                errorMessage = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }
    }

    fun refresh() {
        val adapter = serviceAdapter ?: return
        serviceExecutor.execute {
            val refreshed = runCatching(adapter::snapshot).getOrElse { error ->
                XposedFrameworkSnapshot(
                    errorMessage = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
            }
            if (serviceAdapter === adapter) {
                _snapshot.value = refreshed
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
                            refresh()
                            callback(approved.toSet(), null)
                        }
                    },
                    onFailure = { message ->
                        mainHandler.post {
                            refresh()
                            callback(emptySet(), message.ifBlank { "作用域申请失败" })
                        }
                    },
                )
            }.onFailure { error ->
                mainHandler.post {
                    callback(
                        emptySet(),
                        "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                    )
                }
            }
        }
    }

    companion object {
        val instance: XposedStatusRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            XposedStatusRepository()
        }
    }
}
