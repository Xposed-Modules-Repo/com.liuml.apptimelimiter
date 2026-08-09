package com.liuml.apptimelimiter.nonroot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.data.RuleRepository
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuExecutionState {
    DISABLED,
    UNAVAILABLE,
    PERMISSION_REQUIRED,
    CONNECTING,
    READY,
    FAILED,
}

enum class ShizukuExecutionResult {
    SUCCESS,
    UNAVAILABLE,
    PERMISSION_REQUIRED,
    REJECTED,
    FAILED,
}

class ShizukuExecutionRepository private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext, ShizukuExecutionUserService::class.java),
        )
            .processNameSuffix("time_stop")
            .daemon(false)
            .tag(USER_SERVICE_TAG)
            .version(BuildConfig.VERSION_CODE)
    }
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "time-stop-shizuku").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(ShizukuExecutionState.DISABLED)
    @Volatile
    private var remote: IShizukuExecutionService? = null
    @Volatile
    private var binding = false
    @Volatile
    private var repairOnlyBinding = false
    @Volatile
    private var pendingRepair: PendingRepair? = null

    val state: StateFlow<ShizukuExecutionState> = _state.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        binding = false
        refresh()
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refresh()
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IShizukuExecutionService.Stub.asInterface(service)
            binding = false
            _state.value = ShizukuExecutionState.READY
            val repair = pendingRepair
            if (repair != null) {
                pendingRepair = null
                execute(repair.packageName) { result ->
                    repair.callback(result)
                    finishRepairBinding()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            binding = false
            refresh()
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
    }

    fun refresh() {
        val enabled = RuleRepository(appContext).getGlobalSettings().protectionMode.usesShizuku
        if (!enabled && pendingRepair == null && !repairOnlyBinding) {
            if (remote != null || binding) {
                runCatching {
                    Shizuku.unbindUserService(userServiceArgs, connection, true)
                }
            }
            remote = null
            binding = false
            _state.value = ShizukuExecutionState.DISABLED
            return
        }
        if (!runCatching(Shizuku::pingBinder).getOrDefault(false)) {
            remote = null
            binding = false
            _state.value = ShizukuExecutionState.UNAVAILABLE
            return
        }
        if (
            runCatching(Shizuku::checkSelfPermission).getOrDefault(
                PackageManager.PERMISSION_DENIED,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            remote = null
            binding = false
            _state.value = ShizukuExecutionState.PERMISSION_REQUIRED
            return
        }
        if (remote != null) {
            _state.value = ShizukuExecutionState.READY
        } else {
            bindUserService()
        }
    }

    fun requestPermission(requestCode: Int = REQUEST_CODE) {
        if (!runCatching(Shizuku::pingBinder).getOrDefault(false)) {
            _state.value = ShizukuExecutionState.UNAVAILABLE
            return
        }
        runCatching { Shizuku.requestPermission(requestCode) }
            .onFailure { _state.value = ShizukuExecutionState.FAILED }
    }

    fun repairCapabilityState(): ShizukuExecutionState {
        if (!runCatching(Shizuku::pingBinder).getOrDefault(false)) {
            return ShizukuExecutionState.UNAVAILABLE
        }
        return if (
            runCatching(Shizuku::checkSelfPermission).getOrDefault(
                PackageManager.PERMISSION_DENIED,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            ShizukuExecutionState.READY
        } else {
            ShizukuExecutionState.PERMISSION_REQUIRED
        }
    }

    fun forceStopForRepair(
        packageName: String,
        callback: (ShizukuExecutionResult) -> Unit,
    ) {
        val validation = validateTarget(packageName)
        if (validation != null) {
            callback(validation)
            return
        }
        when (repairCapabilityState()) {
            ShizukuExecutionState.UNAVAILABLE -> {
                callback(ShizukuExecutionResult.UNAVAILABLE)
                return
            }
            ShizukuExecutionState.PERMISSION_REQUIRED -> {
                callback(ShizukuExecutionResult.PERMISSION_REQUIRED)
                return
            }
            else -> Unit
        }
        if (remote != null) {
            execute(packageName, callback)
            return
        }
        val request = PendingRepair(packageName, callback)
        pendingRepair = request
        repairOnlyBinding = true
        bindUserService()
        mainHandler.postDelayed(
            {
                if (pendingRepair === request) {
                    pendingRepair = null
                    callback(ShizukuExecutionResult.FAILED)
                    finishRepairBinding()
                }
            },
            REPAIR_BIND_TIMEOUT_MILLIS,
        )
    }

    fun forceStop(
        packageName: String,
        callback: (ShizukuExecutionResult) -> Unit,
    ) {
        val validation = validateTarget(packageName)
        if (validation != null) {
            callback(validation)
            return
        }
        when (_state.value) {
            ShizukuExecutionState.DISABLED,
            ShizukuExecutionState.UNAVAILABLE,
            -> callback(ShizukuExecutionResult.UNAVAILABLE)
            ShizukuExecutionState.PERMISSION_REQUIRED ->
                callback(ShizukuExecutionResult.PERMISSION_REQUIRED)
            ShizukuExecutionState.READY -> execute(packageName, callback)
            // Enforcement must never wait indefinitely for a UserService connection. Report a
            // bounded failure so the coordinator can immediately show the ordinary restriction
            // page; a later connection remains available for the next enforcement event.
            ShizukuExecutionState.CONNECTING -> callback(ShizukuExecutionResult.FAILED)
            ShizukuExecutionState.FAILED -> callback(ShizukuExecutionResult.FAILED)
        }
    }

    private fun validateTarget(packageName: String): ShizukuExecutionResult? {
        if (packageName.isBlank()) return ShizukuExecutionResult.REJECTED
        val applicationInfo = runCatching {
            appContext.packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull() ?: return ShizukuExecutionResult.REJECTED
        val launcherPackage = appContext.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        val allowed = ShizukuTargetPolicy.isAllowed(
            packageName = packageName,
            ownPackageName = appContext.packageName,
            configuredPackages = RuleRepository(appContext).configuredPackages(),
            systemOrUpdatedSystemApp =
                applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                    applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            launcherPackage = launcherPackage,
            protectedPackages = PROTECTED_PACKAGES,
        )
        return if (allowed) null else ShizukuExecutionResult.REJECTED
    }

    private fun bindUserService() {
        if (binding) return
        binding = true
        _state.value = ShizukuExecutionState.CONNECTING
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure {
                binding = false
                _state.value = ShizukuExecutionState.FAILED
                pendingRepair?.let { repair ->
                    pendingRepair = null
                    repair.callback(ShizukuExecutionResult.FAILED)
                }
                finishRepairBinding()
            }
    }

    private fun finishRepairBinding() {
        if (!repairOnlyBinding) return
        repairOnlyBinding = false
        if (!RuleRepository(appContext).getGlobalSettings().protectionMode.usesShizuku) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
            remote = null
            binding = false
            _state.value = ShizukuExecutionState.DISABLED
        }
    }

    private fun execute(
        packageName: String,
        callback: (ShizukuExecutionResult) -> Unit,
    ) {
        val service = remote
        if (service == null) {
            bindUserService()
            callback(ShizukuExecutionResult.FAILED)
            return
        }
        executor.execute {
            val result = runCatching {
                service.forceStopPackage(
                    packageName,
                    Process.myUid() / PER_USER_RANGE,
                )
            }.getOrDefault(ShizukuExecutionUserService.RESULT_FAILED)
            callback(
                when (result) {
                    ShizukuExecutionUserService.RESULT_OK -> ShizukuExecutionResult.SUCCESS
                    ShizukuExecutionUserService.RESULT_REJECTED ->
                        ShizukuExecutionResult.REJECTED
                    else -> ShizukuExecutionResult.FAILED
                },
            )
        }
    }

    companion object {
        private const val REQUEST_CODE = 9023
        private const val USER_SERVICE_TAG = "time-stop-force-stop"
        private val PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
        )
        // Android allocates app UIDs in fixed per-user ranges; no public UserHandle ID accessor
        // exists on all supported API levels.
        private const val PER_USER_RANGE = 100_000
        private const val REPAIR_BIND_TIMEOUT_MILLIS = 3_000L
        @Volatile
        private var instance: ShizukuExecutionRepository? = null

        fun get(context: Context): ShizukuExecutionRepository =
            instance ?: synchronized(this) {
                instance ?: ShizukuExecutionRepository(context).also { instance = it }
            }
    }

    private data class PendingRepair(
        val packageName: String,
        val callback: (ShizukuExecutionResult) -> Unit,
    )
}
