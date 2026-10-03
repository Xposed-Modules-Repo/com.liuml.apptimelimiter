package com.liuml.apptimelimiter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.liuml.apptimelimiter.core.RestrictionExecutionResult
import com.liuml.apptimelimiter.nonroot.RootExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit, per-package maintenance. Never changes the automatic Root enhancement setting. */
@Composable
internal fun HookReloadControls(
    packages: List<String>,
    targets: List<com.liuml.apptimelimiter.core.TargetProtectionStatus>,
    onRequestScope: (Set<String>) -> Unit,
    onRefreshStatus: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refreshStatus by rememberUpdatedState(onRefreshStatus)
    val currentPackages by rememberUpdatedState(packages)
    var pending by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    val names by produceState<Map<String, String>>(emptyMap(), packages) {
        value = withContext(Dispatchers.IO) {
            packages.associateWith { pkg -> runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg) }
        }
    }
    val chinese = context.resources.configuration.locales[0].language == "zh"
    fun label(zh: String, en: String) = if (chinese) zh else en
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (packages.isNotEmpty()) {
            Text(label(
                "仅处理 Hook 异常或等待重开的应用。Root 强停后立即刷新状态，请手动重开应用验证；不会开启自动 Root 增强。",
                "Only Hooks needing repair or reopening are listed. Root force-stop refreshes status immediately; reopen the app to verify. Automatic Root enhancement stays unchanged.",
            ), style = MaterialTheme.typography.bodySmall)
            packages.forEach { target ->
                Column {
                    Text(names[target] ?: target, style = MaterialTheme.typography.titleSmall)
                    Text(target, style = MaterialTheme.typography.bodySmall)
                    val status = targets.firstOrNull { it.packageName == target }
                    Text(when {
                        status?.scopeState == com.liuml.apptimelimiter.core.ScopeState.NOT_IN_SCOPE -> label("尚未加入作用域，请先加入。", "Not in scope. Add it first.")
                        status?.hookState == com.liuml.apptimelimiter.core.HookVerificationState.OUTDATED -> label("Hook 版本过旧，强停重开以加载新版。", "Outdated Hook. Force stop and reopen to load the update.")
                        status?.hookState == com.liuml.apptimelimiter.core.HookVerificationState.FAILED -> label("Hook 加载失败，请检查模块启用状态后重开。", "Hook failed to load. Check module activation, then reopen.")
                        status?.hookState == com.liuml.apptimelimiter.core.HookVerificationState.PENDING_REOPEN -> label("等待重新打开应用验证。", "Reopen the app to verify.")
                        else -> label("强停后重新打开，刷新状态以验证 Hook。", "Force stop, reopen, then refresh to verify the Hook.")
                    }, style = MaterialTheme.typography.bodySmall)
                    if (status?.scopeState == com.liuml.apptimelimiter.core.ScopeState.NOT_IN_SCOPE) {
                        TextButton(onClick = { onRequestScope(setOf(target)) }, enabled = pending == null) {
                            Text(label("加入作用域", "Add to scope"))
                        }
                    }
                    TextButton(enabled = pending == null, onClick = {
                        pending = target
                        message = label("请在 Root 管理器中授权时停…", "Authorize Time Stop in your root manager…")
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val executor = RootExecutor(context)
                                    if (!executor.requestAuthorization()) null
                                    else if (target !in currentPackages) RestrictionExecutionResult.REJECTED
                                    else executor.forceStopForHookReload(target)
                                }
                                message = when (result) {
                                    null -> label("未获得 Root 或授权超时，未执行强停。", "Root unavailable, denied or timed out; no force-stop performed.")
                                    RestrictionExecutionResult.EXECUTED -> label("已强行停止 $target，请重新打开应用验证 Hook。", "$target stopped. Reopen the app to verify its Hook.")
                                    RestrictionExecutionResult.REJECTED -> label("目标不允许强停，或当前已不是 LSPosed 模式。", "Target is protected or LSPosed mode is no longer active.")
                                    else -> label("强停失败、执行超时或仍有进程运行，请检查 Root 授权后重试。", "Force-stop failed, timed out or processes remain. Check Root access and retry.")
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                message = label("强停失败，请检查 Root 授权后重试。", "Force-stop failed. Check Root access and retry.")
                            } finally {
                                pending = null
                                refreshStatus()
                            }
                        }
                    }) {
                        Text(if (pending == target) label("正在处理…", "Working…") else label("强行停止并刷新（Root）", "Force stop and refresh (Root)"))
                    }
                }
            }
        }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
