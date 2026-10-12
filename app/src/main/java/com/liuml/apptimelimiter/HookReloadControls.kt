package com.liuml.apptimelimiter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
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
    val messages = remember { mutableStateMapOf<String, String>() }
    val currentTargets by rememberUpdatedState(targets)
    val apps by produceState<Map<String, Pair<String, android.graphics.Bitmap?>>>(emptyMap(), packages) {
        value = withContext(Dispatchers.IO) {
            packages.associateWith { pkg -> runCatching {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString() to
                    runCatching { context.packageManager.getApplicationIcon(info).toBitmap(96, 96) }.getOrNull()
            }.getOrDefault(pkg to null) }
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
                key(target) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val name = apps[target]?.first ?: target
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        apps[target]?.second?.let { bitmap ->
                            Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
                        }
                        Text(name, style = MaterialTheme.typography.titleSmall)
                    }
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
                    TextButton(enabled = pending == null && status?.scopeState != com.liuml.apptimelimiter.core.ScopeState.NOT_IN_SCOPE, onClick = {
                        pending = target
                        messages[target] = label("正在停止「$name」；请在 Root 管理器中授权时停…", "Stopping $name; authorize Time Stop in your root manager…")
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val executor = RootExecutor(context)
                                    if (!executor.requestAuthorization()) null
                                    else if (target !in currentPackages || target !in com.liuml.apptimelimiter.core.HookReloadTargetPolicy.packages(currentTargets)) RestrictionExecutionResult.REJECTED
                                    else executor.forceStopForHookReload(target)
                                }
                                messages[target] = when (result) {
                                    null -> label("未获得 Root 或授权超时，未执行强停。", "Root unavailable, denied or timed out; no force-stop performed.")
                                    RestrictionExecutionResult.EXECUTED -> label("已停止「$name」（$target），等待手动重开验证 Hook。", "$name ($target) stopped. Reopen it to verify its Hook.")
                                    RestrictionExecutionResult.REJECTED -> label("目标不允许强停，或当前已不是 LSPosed 模式。", "Target is protected or LSPosed mode is no longer active.")
                                    else -> label("未能确认「$name」的所有进程已停止，可能超时或检测受限。已刷新状态，请重开此应用验证 Hook。", "Could not confirm all $name processes stopped; execution may have timed out or verification was unavailable. Status refreshed; reopen this app to verify its Hook.")
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                messages[target] = label("强停失败，请检查 Root 授权后重试。", "Force-stop failed. Check Root access and retry.")
                            } finally {
                                pending = null
                                refreshStatus()
                            }
                        }
                    }) {
                        Text(if (pending == target) label("正在停止「$name」…", "Stopping $name…") else label("强停「$name」并刷新（Root）", "Force stop $name and refresh (Root)"))
                    }
                    messages[target]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                }
                }
            }
        }
        // Status refresh may remove the repaired card; retain its package-bound receipt.
        messages.filterKeys { it !in packages }.forEach { (pkg, result) ->
            Text("$pkg\n$result", style = MaterialTheme.typography.bodySmall)
        }
    }
}
