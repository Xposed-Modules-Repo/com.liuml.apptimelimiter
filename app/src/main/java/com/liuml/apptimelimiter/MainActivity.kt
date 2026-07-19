package com.liuml.apptimelimiter

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.GlobalSettings
import com.liuml.apptimelimiter.data.InstalledApp
import com.liuml.apptimelimiter.data.InstalledAppsRepository
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.data.ScheduleWindow
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.support.FeedbackSender
import com.liuml.apptimelimiter.settings.LauncherIconController
import com.liuml.apptimelimiter.statistics.AppUsageSummary
import com.liuml.apptimelimiter.statistics.CalculatedUsageSummary
import com.liuml.apptimelimiter.statistics.DeviceUsageStatsRepository
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import com.liuml.apptimelimiter.update.ReleaseInfo
import com.liuml.apptimelimiter.update.UpdateCheckResult
import com.liuml.apptimelimiter.update.UpdateChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ruleRepository = RuleRepository(this)
        val diagnosticsRepository = DiagnosticsRepository(this)
        val usageStatsRepository = UsageStatsRepository(this)
        val deviceUsageStatsRepository = DeviceUsageStatsRepository(this)
        val installedAppsRepository = InstalledAppsRepository(this)
        setContent {
            var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
            LaunchedEffect(Unit) {
                apps = withContext(Dispatchers.IO) {
                    installedAppsRepository.loadLaunchableApps()
                }
            }
            AppTimeLimiterTheme {
                val loadedApps = apps
                if (loadedApps == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    TimeLimiterScreen(
                        loadedApps,
                        ruleRepository,
                        diagnosticsRepository,
                        usageStatsRepository,
                        deviceUsageStatsRepository,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TimeLimiterScreen(
    apps: List<InstalledApp>,
    repository: RuleRepository,
    diagnosticsRepository: DiagnosticsRepository,
    usageStatsRepository: UsageStatsRepository,
    deviceUsageStatsRepository: DeviceUsageStatsRepository,
) {
    val context = LocalContext.current
    val rules = remember {
        mutableStateMapOf<String, AppRule>().also { map ->
            apps.forEach { map[it.packageName] = repository.getRule(it.packageName) }
        }
    }
    var search by remember { mutableStateOf("") }
    var onlyEnabled by remember { mutableStateOf(false) }
    var showSystemApps by remember {
        mutableStateOf(
            context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getBoolean("show_system_apps", false),
        )
    }
    var editingApp by remember { mutableStateOf<InstalledApp?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var selectedSection by remember { mutableStateOf(MainSection.HOME) }
    var usageRevision by remember { mutableIntStateOf(0) }
    var showSetupGuide by remember {
        mutableStateOf(
            !context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getBoolean("setup_guide_acknowledged", false),
        )
    }

    val visibleApps = remember(apps, search, onlyEnabled, showSystemApps, rules.toMap()) {
        apps.filter { app ->
            (showSystemApps || !app.isSystemApp) &&
            (!onlyEnabled || rules[app.packageName]?.enabled == true) &&
                (search.isBlank() || app.label.contains(search, true) || app.packageName.contains(search, true))
        }.sortedWith(
            compareByDescending<InstalledApp> { rules[it.packageName]?.enabled == true }
                .thenBy { it.label.lowercase() },
        )
    }
    val enabledPackages = rules.values.filter(AppRule::enabled).map(AppRule::packageName).toSet()
    val statsEnabled = remember(usageRevision) {
        repository.getGlobalSettings().usageStatsEnabled
    }
    val usageAccessGranted = remember(usageRevision) {
        deviceUsageStatsRepository.hasUsageAccess()
    }
    val hookSummaries = remember(enabledPackages, usageRevision) {
        usageStatsRepository.summariesToday(enabledPackages)
    }
    val allLaunchablePackages = remember(apps) { apps.map(InstalledApp::packageName).toSet() }
    var systemSummaries by remember {
        mutableStateOf<Map<String, CalculatedUsageSummary>>(emptyMap())
    }
    LaunchedEffect(allLaunchablePackages, usageRevision, usageAccessGranted, statsEnabled) {
        systemSummaries = if (statsEnabled && usageAccessGranted) {
            withContext(Dispatchers.IO) {
                deviceUsageStatsRepository.todayUsageSummaries(allLaunchablePackages)
            }
        } else {
            emptyMap()
        }
    }
    val todaySummaries = remember(hookSummaries, systemSummaries, usageAccessGranted) {
        val hookByPackage = hookSummaries.associateBy(AppUsageSummary::packageName)
        apps.mapNotNull { app ->
            val hook = hookByPackage[app.packageName] ?: AppUsageSummary(
                packageName = app.packageName,
                durationMillis = 0L,
                launchCount = 0,
                limitHitCount = 0,
                lastUsedAtMillis = 0L,
            )
            val system = systemSummaries[app.packageName]
            hook.copy(
                durationMillis = if (usageAccessGranted) system?.durationMillis ?: 0L else 0L,
                launchCount = system?.launchCount ?: hook.launchCount,
                lastUsedAtMillis = maxOf(hook.lastUsedAtMillis, system?.lastUsedAtMillis ?: 0L),
            )
                .takeIf { it.durationMillis > 0L || it.launchCount > 0 || it.limitHitCount > 0 }
        }
    }
    val todayTotalMillis = todaySummaries.sumOf(AppUsageSummary::durationMillis)
    val latestHookHeartbeat = remember(enabledPackages, usageRevision) {
        usageStatsRepository.latestHookHeartbeat(enabledPackages)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) usageRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val nextMidnight = LocalDate.now()
                .plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            delay((nextMidnight - System.currentTimeMillis() + 250L).coerceAtLeast(1_000L))
            usageRevision++
        }
    }
    LaunchedEffect(selectedSection, statsEnabled, usageAccessGranted) {
        while (
            selectedSection == MainSection.STATS &&
            statsEnabled &&
            usageAccessGranted
        ) {
            delay(STATS_REFRESH_INTERVAL_MS)
            usageRevision++
        }
    }

    Scaffold(
        containerColor = Color(0xFFF7F8FC),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (selectedSection) {
                                MainSection.HOME -> "时停"
                                MainSection.APPS -> "应用管理"
                                MainSection.STATS -> "使用统计"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when (selectedSection) {
                                MainSection.HOME -> "应用使用时长管控"
                                MainSection.APPS -> "已启用 ${enabledPackages.size} 个应用"
                                MainSection.STATS -> "展示今日使用过的全部应用"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7F8FC)),
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("设置") }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFFF1ECF8)) {
                MainSection.entries.forEach { section ->
                    val selected = selectedSection == section
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            selectedSection = section
                            usageRevision++
                        },
                        icon = {
                            Text(
                                when (section) {
                                    MainSection.HOME -> "⌂"
                                    MainSection.APPS -> "▦"
                                    MainSection.STATS -> "▥"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        label = { Text(section.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (selectedSection) {
            MainSection.HOME -> HomeDashboard(
                modifier = Modifier.fillMaxSize().padding(padding),
                enabledCount = enabledPackages.size,
                todayTotalMillis = todayTotalMillis,
                latestHookHeartbeat = latestHookHeartbeat,
                statsEnabled = statsEnabled,
                usageAccessGranted = usageAccessGranted,
                onRequestUsageAccess = deviceUsageStatsRepository::openUsageAccessSettings,
                onManageApps = { selectedSection = MainSection.APPS },
                onOpenStats = {
                    usageRevision++
                    selectedSection = MainSection.STATS
                },
                onOpenGuide = { showSetupGuide = true },
                onOpenLogs = { showLogs = true },
            )

            MainSection.APPS -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    TextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索应用或包名") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = onlyEnabled,
                            onClick = { onlyEnabled = !onlyEnabled },
                            label = { Text("仅看已启用") },
                        )
                        FilterChip(
                            selected = showSystemApps,
                            onClick = {
                                showSystemApps = !showSystemApps
                                context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("show_system_apps", showSystemApps)
                                    .apply()
                            },
                            label = { Text("显示系统应用") },
                        )
                    }
                }

                items(visibleApps, key = { it.packageName }) { app ->
                    val rule = rules.getValue(app.packageName)
                    AppRow(
                        app = app,
                        rule = rule,
                        onClick = { editingApp = app },
                        onToggle = { enabled ->
                            if (enabled) {
                                editingApp = app
                            } else {
                                val updated = rule.copy(enabled = false)
                                repository.save(updated)
                                if (repository.getGlobalSettings().diagnosticsEnabled) {
                                    diagnosticsRepository.append(
                                        "INFO",
                                        app.packageName,
                                        "RULE_SAVED",
                                        "规则已停用",
                                    )
                                }
                                rules[app.packageName] = repository.getRule(app.packageName)
                                usageRevision++
                            }
                        },
                    )
                }
            }

            MainSection.STATS -> UsageStatisticsScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                apps = apps,
                summaries = todaySummaries,
                controlledPackages = enabledPackages,
                statsEnabled = statsEnabled,
                usageAccessGranted = usageAccessGranted,
                onRequestUsageAccess = deviceUsageStatsRepository::openUsageAccessSettings,
                onClear = {
                    usageStatsRepository.clearAll()
                    usageRevision++
                },
            )
        }
    }

    editingApp?.let { app ->
        RuleDialog(
            app = app,
            initialRule = rules.getValue(app.packageName),
            onDismiss = { editingApp = null },
            onSave = { configuredRule ->
                repository.save(configuredRule)
                if (repository.getGlobalSettings().diagnosticsEnabled) {
                    diagnosticsRepository.append(
                        "INFO",
                        app.packageName,
                        "RULE_SAVED",
                        "enabled=${configuredRule.enabled}, daily=${configuredRule.dailyEnabled}/${configuredRule.dailyLimitSeconds}s, perLaunch=${configuredRule.perLaunchEnabled}/${configuredRule.perLaunchLimitSeconds}s, schedule=${configuredRule.scheduleEnabled}/${configuredRule.scheduleMode}/${configuredRule.scheduleWindows.size}, cooldown=${configuredRule.cooldownEnabled}/${configuredRule.cooldownSeconds}s",
                    )
                }
                rules[app.packageName] = repository.getRule(app.packageName)
                editingApp = null
            },
        )
    }

    if (showLogs) {
        DiagnosticLogDialog(
            repository = diagnosticsRepository,
            onFeedback = { FeedbackSender.send(context, diagnosticsRepository) },
            onDismiss = { showLogs = false },
        )
    }

    if (showSettings) {
        SettingsDialog(
            initialSettings = repository.getGlobalSettings(),
            usageAccessGranted = usageAccessGranted,
            onDismiss = { showSettings = false },
            onDonate = { openAlipayDonation(context) },
            onCheckUpdates = {
                showSettings = false
                checkingUpdate = true
                UpdateChecker.check { result ->
                    checkingUpdate = false
                    updateResult = result
                }
            },
            onAbout = {
                showSettings = false
                showAbout = true
            },
            onFeedback = { FeedbackSender.send(context, diagnosticsRepository) },
            onCopyRecoveryCommand = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText("恢复应用入口", LauncherIconController.RECOVERY_COMMAND),
                )
                Toast.makeText(context, "恢复命令已复制", Toast.LENGTH_SHORT).show()
            },
            onRequestUsageAccess = deviceUsageStatsRepository::openUsageAccessSettings,
            onSave = saveSettings@{ settings ->
                val iconResult = runCatching {
                    LauncherIconController.setHidden(context, settings.launcherIconHidden)
                }
                if (iconResult.isFailure) {
                    Toast.makeText(
                        context,
                        "修改桌面图标失败：${iconResult.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@saveSettings
                }
                repository.saveGlobalSettings(settings)
                if (settings.diagnosticsEnabled) {
                    diagnosticsRepository.append(
                        "INFO",
                        context.packageName,
                        "SETTINGS_SAVED",
                        "warning=${settings.exitWarningEnabled}, extension=${settings.extensionSeconds}s, diagnostics=true, iconHidden=${settings.launcherIconHidden}, usageStats=${settings.usageStatsEnabled}",
                    )
                }
                if (settings.launcherIconHidden) {
                    Toast.makeText(
                        context,
                        "桌面图标已关闭，可从 LSPosed 模块页打开应用",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                usageRevision++
                showSettings = false
            },
        )
    }

    if (checkingUpdate) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("检查更新") },
            text = { Text("正在连接 GitHub…") },
            confirmButton = {},
        )
    }

    updateResult?.let { result ->
        UpdateResultDialog(
            result = result,
            onDismiss = { updateResult = null },
            onDownload = { release ->
                runCatching { UpdateChecker.download(context, release) }
                    .onSuccess {
                        Toast.makeText(
                            context,
                            "已加入系统下载队列；完成后点击系统通知安装",
                            Toast.LENGTH_LONG,
                        ).show()
                        updateResult = null
                    }
                    .onFailure {
                        Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_LONG).show()
                    }
            },
            onOpenPage = { release -> openUrl(context, release.pageUrl) },
        )
    }

    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onOpenRepository = { openUrl(context, UpdateChecker.REPOSITORY_URL) },
            onFeedback = { FeedbackSender.send(context, diagnosticsRepository) },
        )
    }

    if (showSetupGuide) {
        SetupGuideDialog(
            onDismiss = {
                context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("setup_guide_acknowledged", true)
                    .apply()
                showSetupGuide = false
            },
        )
    }
}

private enum class MainSection(val label: String) {
    HOME("首页"),
    APPS("应用"),
    STATS("统计"),
}

@Composable
private fun HomeDashboard(
    modifier: Modifier,
    enabledCount: Int,
    todayTotalMillis: Long,
    latestHookHeartbeat: Long,
    statsEnabled: Boolean,
    usageAccessGranted: Boolean,
    onRequestUsageAccess: () -> Unit,
    onManageApps: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val hookRecentlyVerified = latestHookHeartbeat > 0L &&
        System.currentTimeMillis() - latestHookHeartbeat <= HOOK_VERIFIED_WINDOW_MS
    val statusTitle = when {
        enabledCount == 0 -> "未启用管控"
        hookRecentlyVerified -> "管控运行中"
        else -> "等待 Hook 验证"
    }
    val statusDescription = when {
        enabledCount == 0 -> "请先选择需要管控的应用"
        hookRecentlyVerified -> "LSPosed Hook 已在目标应用中运行"
        else -> "打开一次已管控应用以确认模块状态"
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    if (enabledCount == 0) onManageApps() else onOpenGuide()
                },
                colors = CardDefaults.cardColors(
                    containerColor = if (hookRecentlyVerified) Color(0xFFE0F4E8) else Color(0xFFE9DDFB),
                ),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = if (hookRecentlyVerified) Color(0xFF19734A) else Color(0xFF351078),
                        shape = CircleShape,
                    ) {
                        Text(
                            if (hookRecentlyVerified) "✓" else "◆",
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(statusTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        statusDescription,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (statsEnabled && !usageAccessGranted) {
            item {
                UsageAccessCard(onRequestUsageAccess)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DashboardMetricCard(
                    modifier = Modifier.weight(1f),
                    symbol = "◷",
                    symbolColor = Color(0xFFF59E0B),
                    value = formatDashboardDuration(todayTotalMillis),
                    label = "今日总使用",
                    onClick = onOpenStats,
                )
                DashboardMetricCard(
                    modifier = Modifier.weight(1f),
                    symbol = "▦",
                    symbolColor = Color(0xFF3FA34D),
                    value = "$enabledCount 个",
                    label = "管控应用数",
                    onClick = onManageApps,
                )
            }
        }
        item {
            Text("快捷操作", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            DashboardActionCard(
                symbol = "▦",
                title = "管理应用",
                description = "选择需要管控的应用并设置时间限制",
                onClick = onManageApps,
            )
        }
        item {
            DashboardActionCard(
                symbol = "▥",
                title = "使用统计",
                description = "查看各应用今天的使用时长记录",
                onClick = onOpenStats,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenGuide) { Text("配置要求") }
                OutlinedButton(onClick = onOpenLogs) { Text("诊断日志") }
            }
        }
    }
}

@Composable
private fun UsageAccessCard(onRequestUsageAccess: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE9C7)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("需要使用情况访问权限", fontWeight = FontWeight.Bold)
            Text(
                "授权后由 Android 系统提供今日使用时长，仅在打开时停时读取，不需要后台服务。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRequestUsageAccess) { Text("去授权") }
        }
    }
}

@Composable
private fun DashboardMetricCard(
    modifier: Modifier,
    symbol: String,
    symbolColor: Color,
    value: String,
    label: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE9E7EC)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(symbol, color = symbolColor, style = MaterialTheme.typography.headlineMedium)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardActionCard(
    symbol: String,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color(0xFFE9E7EC),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(symbol, color = Color(0xFF6548B5), style = MaterialTheme.typography.headlineMedium)
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageStatisticsScreen(
    modifier: Modifier,
    apps: List<InstalledApp>,
    summaries: List<AppUsageSummary>,
    controlledPackages: Set<String>,
    statsEnabled: Boolean,
    usageAccessGranted: Boolean,
    onRequestUsageAccess: () -> Unit,
    onClear: () -> Unit,
) {
    val appByPackage = remember(apps) { apps.associateBy(InstalledApp::packageName) }
    val sorted = summaries.sortedWith(
        compareByDescending<AppUsageSummary> { it.packageName in controlledPackages }
            .thenByDescending { it.durationMillis }
            .thenByDescending { it.lastUsedAtMillis },
    )
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!statsEnabled) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE9C7))) {
                    Text(
                        "使用统计已在设置中关闭，以下数据不会继续更新。",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else if (!usageAccessGranted) {
            item {
                UsageAccessCard(onRequestUsageAccess)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("今日总使用", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatDashboardDuration(sorted.sumOf(AppUsageSummary::durationMillis)),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (usageAccessGranted) "Android 系统按需读取 · 无后台服务" else "授权后显示系统使用时长",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClear) { Text("清空模块记录") }
            }
        }
        if (sorted.isEmpty()) {
            item { Text("今天暂无应用使用记录。") }
        } else {
            items(sorted, key = AppUsageSummary::packageName) { summary ->
                val app = appByPackage[summary.packageName]
                val controlled = summary.packageName in controlledPackages
                val currentHookReported = summary.lastHookEventAtMillis > 0L &&
                    summary.hookVersionCode >= BuildConfig.VERSION_CODE
                val hookReportMissing = controlled && !currentHookReported &&
                    (summary.durationMillis > 0L || summary.launchCount > 0 || summary.limitHitCount > 0)
                Surface(
                    color = if (controlled) Color(0xFFF2F6FF) else Color.White,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (app != null) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                AppIcon(app)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    app?.label ?: summary.packageName,
                                    modifier = Modifier.weight(1f, fill = false),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (controlled) ManagedBadge()
                            }
                            Text(
                                if (controlled) {
                                    "启动 ${summary.launchCount} 次 · 限制触发 ${summary.limitHitCount} 次"
                                } else {
                                    "Android 系统使用统计"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (hookReportMissing) {
                                Text(
                                    "Hook 计数未同步，请强停该应用后重新打开",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFB54708),
                                )
                            }
                        }
                        Text(formatDashboardDuration(summary.durationMillis), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatDashboardDuration(durationMillis: Long): String {
    val totalMinutes = durationMillis.coerceAtLeast(0L) / 60_000L
    return if (totalMinutes < 60L) {
        "${totalMinutes}分"
    } else {
        "${totalMinutes / 60L}时${totalMinutes % 60L}分"
    }
}

private const val HOOK_VERIFIED_WINDOW_MS = 15L * 60L * 1000L
private const val STATS_REFRESH_INTERVAL_MS = 15_000L

@Composable
private fun SetupCard(
    onOpenGuide: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FF)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("需要 LSPosed", fontWeight = FontWeight.Bold, color = Color(0xFF154B99))
            Text(
                "保存规则后，请在 LSPosed 中启用本模块并勾选目标应用。首次启用或修改作用域后，需要强制停止目标应用再打开。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF294D7A),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenGuide) { Text("配置要求") }
                Button(onClick = onOpenLogs) { Text("诊断日志") }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    rule: AppRule,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (rule.enabled) Color(0xFFF2F6FF) else Color.White,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = if (rule.enabled) Color(0xFF315EA8) else Color(0xFFE6E8EE),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(app = app)
                }
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        app.label,
                        modifier = Modifier.weight(1f, fill = false),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (rule.enabled) ManagedBadge()
                }
                Text(
                    if (rule.enabled) {
                        ruleSummary(rule)
                    } else {
                        app.packageName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ManagedBadge() {
    Surface(
        color = Color(0xFF315EA8),
        contentColor = Color.White,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            "管控中",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AppIcon(app: InstalledApp) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val bitmap = remember(app.packageName, sizePx) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName)
                .toBitmap(sizePx, sizePx)
                .asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "${app.label}图标",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(3.dp).clip(CircleShape),
        )
    } else {
        Text(app.label.take(1).uppercase(), fontWeight = FontWeight.Bold)
    }
}

private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
    if (this is BitmapDrawable && bitmap.width == width && bitmap.height == height) return bitmap
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
        val canvas = Canvas(target)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
    }
}

@Composable
private fun SetupGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行前需要完成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("本版本不需要相机、存储、通知等 Android 运行时权限。")
                Text("1. 手机已 Root，并安装可用的 LSPosed。")
                Text("2. 在 LSPosed 中启用“时停”模块。")
                Text("3. 在模块作用域中勾选需要限制的目标应用。")
                Text("4. 保存规则后，强制停止目标应用并重新打开。")
                Text(
                    "若诊断日志没有 HOOK_READY，说明 Hook 没有进入目标进程，请先检查第 2、3 项。",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("我知道了") } },
    )
}

@Composable
private fun SettingsDialog(
    initialSettings: GlobalSettings,
    usageAccessGranted: Boolean,
    onDismiss: () -> Unit,
    onDonate: () -> Unit,
    onCheckUpdates: () -> Unit,
    onAbout: () -> Unit,
    onFeedback: () -> Unit,
    onCopyRecoveryCommand: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onSave: (GlobalSettings) -> Unit,
) {
    var warningEnabled by remember { mutableStateOf(initialSettings.exitWarningEnabled) }
    var diagnosticsEnabled by remember { mutableStateOf(initialSettings.diagnosticsEnabled) }
    var launcherIconHidden by remember { mutableStateOf(initialSettings.launcherIconHidden) }
    var usageStatsEnabled by remember { mutableStateOf(initialSettings.usageStatsEnabled) }
    var extensionMinutes by remember {
        mutableStateOf((initialSettings.extensionSeconds / 60L).coerceAtLeast(1L).toString())
    }
    val parsedMinutes = extensionMinutes.toLongOrNull()?.takeIf { it in 1L..60L }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("退出前提醒", fontWeight = FontWeight.Medium)
                        Text("到期前 5 秒在屏幕顶部显示倒计时", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = warningEnabled, onCheckedChange = { warningEnabled = it })
                }
                }
                item {
                HorizontalDivider()
                }
                item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("使用时长统计", fontWeight = FontWeight.Medium)
                        Text(
                            "系统按需读取时长和启动次数，Hook 记录限制触发事件",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = usageStatsEnabled, onCheckedChange = { usageStatsEnabled = it })
                }
                }
                if (usageStatsEnabled) {
                    item {
                        Surface(
                            color = if (usageAccessGranted) Color(0xFFE0F4E8) else Color(0xFFFFE9C7),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (usageAccessGranted) "使用情况访问权限已授予" else "尚未授予使用情况访问权限",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "仅在查看统计或校验每日限额时按需读取，不会常驻后台",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (!usageAccessGranted) {
                                    TextButton(onClick = onRequestUsageAccess) { Text("去授权") }
                                }
                            }
                        }
                    }
                }
                item {
                HorizontalDivider()
                }
                item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("诊断日志", fontWeight = FontWeight.Medium)
                        Text("记录 Hook、计时、延时和退出事件", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = diagnosticsEnabled, onCheckedChange = { diagnosticsEnabled = it })
                }
                }
                item {
                TextField(
                    value = extensionMinutes,
                    onValueChange = { extensionMinutes = it.filter(Char::isDigit).take(2) },
                    label = { Text("每次点击延时（分钟）") },
                    supportingText = { Text("可设置 1–60 分钟；每次点击都会追加") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = extensionMinutes.isNotEmpty() && parsedMinutes == null,
                    enabled = warningEnabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
                item { HorizontalDivider() }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("隐藏桌面图标", fontWeight = FontWeight.Medium)
                            Text(
                                "关闭后可从 LSPosed 模块页打开应用",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = launcherIconHidden,
                            onCheckedChange = { launcherIconHidden = it },
                        )
                    }
                }
                if (launcherIconHidden) {
                    item {
                        Surface(
                            color = Color(0xFFFFE9E7),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "隐藏后请先尝试从 LSPosed 模块页打开设置。若没有入口，可连接电脑执行：",
                                    color = Color(0xFF8C1D18),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    LauncherIconController.RECOVERY_COMMAND,
                                    color = Color(0xFF5F1411),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                TextButton(onClick = onCopyRecoveryCommand) {
                                    Text("复制恢复命令")
                                }
                            }
                        }
                    }
                }
                item { HorizontalDivider() }
                item {
                    SettingsEntry(
                        title = "检查更新",
                        description = "从 GitHub Releases 检查并下载新版 APK",
                        action = "检查 ›",
                        onClick = onCheckUpdates,
                    )
                }
                item {
                    SettingsEntry(
                        title = "反馈问题",
                        description = "通过邮件发送设备信息和诊断日志",
                        action = "反馈 ›",
                        onClick = onFeedback,
                    )
                }
                item {
                    SettingsEntry(
                        title = "关于",
                        description = "版本、项目主页和联系方式",
                        action = "查看 ›",
                        onClick = onAbout,
                    )
                }
                item {
                HorizontalDivider()
                }
                item {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDonate).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("支持开发", fontWeight = FontWeight.Medium)
                        Text(
                            "支付宝捐赠：$DONATION_ACCOUNT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("去转账 ›", color = MaterialTheme.colorScheme.primary)
                }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initialSettings.copy(
                            exitWarningEnabled = warningEnabled,
                            extensionSeconds = (parsedMinutes ?: 5L) * 60L,
                            diagnosticsEnabled = diagnosticsEnabled,
                            launcherIconHidden = launcherIconHidden,
                            usageStatsEnabled = usageStatsEnabled,
                        ),
                    )
                },
                enabled = !warningEnabled || parsedMinutes != null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SettingsEntry(
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(action, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun UpdateResultDialog(
    result: UpdateCheckResult,
    onDismiss: () -> Unit,
    onDownload: (ReleaseInfo) -> Unit,
    onOpenPage: (ReleaseInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (result) {
                    is UpdateCheckResult.Available -> "发现新版本 ${result.release.version}"
                    is UpdateCheckResult.UpToDate -> "已是最新版本"
                    is UpdateCheckResult.Error -> "检查更新失败"
                },
            )
        },
        text = {
            when (result) {
                is UpdateCheckResult.Available -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("当前版本：${BuildConfig.VERSION_NAME}")
                        Text(
                            result.release.notes.ifBlank { "该版本没有填写更新说明。" },
                            modifier = Modifier.heightIn(max = 360.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { onOpenPage(result.release) }) {
                            Text("在 GitHub 查看发布页")
                        }
                    }
                }
                is UpdateCheckResult.UpToDate -> Text(
                    "当前版本 ${BuildConfig.VERSION_NAME}，GitHub 最新版本 ${result.latestVersion}。",
                )
                is UpdateCheckResult.Error -> Text(
                    "${result.message}\n请检查网络连接，或稍后重试。",
                )
            }
        },
        confirmButton = {
            if (result is UpdateCheckResult.Available) {
                Button(onClick = { onDownload(result.release) }) { Text("下载 APK") }
            } else {
                Button(onClick = onDismiss) { Text("确定") }
            }
        },
        dismissButton = {
            if (result is UpdateCheckResult.Available) {
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenRepository: () -> Unit,
    onFeedback: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于时停") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text("Android / LSPosed 应用前台使用时长限制模块。")
                Text("单次打开、每日累计、每周可用时段和退出后冷却可以组合使用。")
                Text("反馈邮箱：${FeedbackSender.EMAIL}")
                TextButton(onClick = onOpenRepository) { Text("打开 GitHub 项目主页") }
                TextButton(onClick = onFeedback) { Text("发送问题反馈") }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure {
            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
}

private fun openAlipayDonation(context: Context) {
    val transferUri = Uri.Builder()
        .scheme("alipays")
        .authority("platformapi")
        .appendPath("startapp")
        .appendQueryParameter("appId", "20000123")
        .appendQueryParameter("actionType", "toAccount")
        .appendQueryParameter("goBack", "YES")
        .appendQueryParameter("account", DONATION_ACCOUNT)
        .build()
    val transferIntent = Intent(Intent.ACTION_VIEW, transferUri)
        .setPackage(ALIPAY_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(transferIntent) }
        .onFailure {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("支付宝账号", DONATION_ACCOUNT))
            val launchIntent = context.packageManager.getLaunchIntentForPackage(ALIPAY_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent != null) runCatching { context.startActivity(launchIntent) }
            Toast.makeText(
                context,
                "支付宝账号已复制：$DONATION_ACCOUNT，请在转账前核对账号",
                Toast.LENGTH_LONG,
            ).show()
        }
}

private const val DONATION_ACCOUNT = "liuml.yx@139.com"
private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

@Composable
private fun DiagnosticLogDialog(
    repository: DiagnosticsRepository,
    onFeedback: () -> Unit,
    onDismiss: () -> Unit,
) {
    var logs by remember { mutableStateOf(repository.readLatest()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志（${logs.size}）") },
        text = {
            if (logs.isEmpty()) {
                Text("暂无日志。打开一次已配置的目标应用；若仍为空，请检查 LSPosed 是否启用模块及目标应用作用域。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(logs) { line ->
                        Text(line.replace('\t', '\n'), style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            Row {
                TextButton(onClick = onFeedback) { Text("反馈") }
                TextButton(onClick = { logs = repository.readLatest() }) { Text("刷新") }
                TextButton(
                    onClick = {
                        repository.clear()
                        logs = emptyList()
                    },
                ) { Text("清空") }
            }
        },
    )
}

@Composable
private fun RuleDialog(
    app: InstalledApp,
    initialRule: AppRule,
    onDismiss: () -> Unit,
    onSave: (AppRule) -> Unit,
) {
    var dailyEnabled by remember(app.packageName) { mutableStateOf(initialRule.dailyEnabled) }
    var dailyMinutes by remember(app.packageName) {
        mutableStateOf((initialRule.dailyLimitSeconds / 60L).coerceAtLeast(1L).toString())
    }
    var perLaunchEnabled by remember(app.packageName) { mutableStateOf(initialRule.perLaunchEnabled) }
    var perLaunchMinutes by remember(app.packageName) {
        mutableStateOf((initialRule.perLaunchLimitSeconds / 60L).coerceAtLeast(1L).toString())
    }
    var scheduleEnabled by remember(app.packageName) { mutableStateOf(initialRule.scheduleEnabled) }
    var scheduleMode by remember(app.packageName) { mutableStateOf(initialRule.scheduleMode) }
    var scheduleWindows by remember(app.packageName) { mutableStateOf(initialRule.scheduleWindows) }
    var cooldownEnabled by remember(app.packageName) { mutableStateOf(initialRule.cooldownEnabled) }
    var cooldownMinutes by remember(app.packageName) {
        mutableStateOf((initialRule.cooldownSeconds / 60L).coerceAtLeast(1L).toString())
    }
    val parsedDailyMinutes = dailyMinutes.toLongOrNull()?.takeIf { it in 1..1440 }
    val parsedPerLaunchMinutes = perLaunchMinutes.toLongOrNull()?.takeIf { it in 1..1440 }
    val parsedCooldownMinutes = cooldownMinutes.toLongOrNull()?.takeIf { it in 1..1440 }
    val scheduleValid = !scheduleEnabled ||
        (scheduleWindows.isNotEmpty() && scheduleWindows.all(ScheduleWindow::isValid))
    val canSave = (dailyEnabled || perLaunchEnabled || scheduleEnabled) &&
        (!dailyEnabled || parsedDailyMinutes != null) &&
        (!perLaunchEnabled || parsedPerLaunchMinutes != null) &&
        (!cooldownEnabled || parsedCooldownMinutes != null) &&
        scheduleValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(app.packageName, style = MaterialTheme.typography.bodySmall) }
                if (app.isSystemApp) {
                    item {
                        Surface(
                            color = Color(0xFFFFE9C7),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                "这是系统应用。达到限制时只关闭应用界面，不结束系统进程，以避免影响桌面或系统稳定性。",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF7A4B00),
                            )
                        }
                    }
                }
                item {
                    ThresholdEditor(
                        title = "每日累计",
                        description = "当天多次打开累计，第二天自动重置",
                        enabled = dailyEnabled,
                        minutes = dailyMinutes,
                        parsedMinutes = parsedDailyMinutes,
                        onEnabledChange = { dailyEnabled = it },
                        onMinutesChange = { dailyMinutes = it },
                    )
                }
                item { HorizontalDivider() }
                item {
                    ThresholdEditor(
                        title = "单次打开",
                        description = "每次目标应用进程启动后重新计时",
                        enabled = perLaunchEnabled,
                        minutes = perLaunchMinutes,
                        parsedMinutes = parsedPerLaunchMinutes,
                        onEnabledChange = { perLaunchEnabled = it },
                        onMinutesChange = { perLaunchMinutes = it },
                    )
                }
                item {
                    Text(
                        "两个限制可同时开启，任何一个先到期都会退出应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item { HorizontalDivider() }
                item {
                    ScheduleEditor(
                        enabled = scheduleEnabled,
                        mode = scheduleMode,
                        windows = scheduleWindows,
                        onEnabledChange = { scheduleEnabled = it },
                        onModeChange = { scheduleMode = it },
                        onWindowsChange = { scheduleWindows = it },
                    )
                }
                item { HorizontalDivider() }
                item {
                    ThresholdEditor(
                        title = "退出后冷却",
                        description = "被时停强制退出后，在设定时间内禁止再次打开",
                        enabled = cooldownEnabled,
                        minutes = cooldownMinutes,
                        parsedMinutes = parsedCooldownMinutes,
                        onEnabledChange = { cooldownEnabled = it },
                        onMinutesChange = { cooldownMinutes = it },
                    )
                }
                if (cooldownEnabled) {
                    item {
                        Text(
                            "冷却期间反复打开不会重新计算冷却时间，也不会重复增加限制触发次数。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initialRule.copy(
                            enabled = true,
                            dailyEnabled = dailyEnabled,
                            dailyLimitSeconds = (parsedDailyMinutes ?: 1L) * 60L,
                            perLaunchEnabled = perLaunchEnabled,
                            perLaunchLimitSeconds = (parsedPerLaunchMinutes ?: 1L) * 60L,
                            scheduleEnabled = scheduleEnabled,
                            scheduleMode = scheduleMode,
                            scheduleWindows = scheduleWindows,
                            cooldownEnabled = cooldownEnabled,
                            cooldownSeconds = (parsedCooldownMinutes ?: 1L) * 60L,
                        ),
                    )
                },
                enabled = canSave,
            ) {
                Text("保存并启用")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onSave(
                            initialRule.copy(
                                enabled = true,
                                dailyEnabled = false,
                                perLaunchEnabled = true,
                                perLaunchLimitSeconds = 10L,
                                scheduleEnabled = false,
                                cooldownEnabled = false,
                            ),
                        )
                    },
                ) { Text("10 秒测试") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ScheduleEditor(
    enabled: Boolean,
    mode: ScheduleMode,
    windows: List<ScheduleWindow>,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (ScheduleMode) -> Unit,
    onWindowsChange: (List<ScheduleWindow>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("可用时段", fontWeight = FontWeight.Medium)
                Text("按星期和时间限制应用是否允许打开", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val allowSelected = mode == ScheduleMode.ALLOW_ONLY
                FilterChip(
                    selected = allowSelected,
                    onClick = { onModeChange(ScheduleMode.ALLOW_ONLY) },
                    label = {
                        Text(
                            if (allowSelected) "✓ 仅指定时段允许" else "仅指定时段允许",
                            fontWeight = if (allowSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFFE5E7EB),
                        labelColor = Color(0xFF6B7280),
                        selectedContainerColor = Color(0xFF19734A),
                        selectedLabelColor = Color.White,
                    ),
                )
                val blockSelected = mode == ScheduleMode.BLOCK_DURING
                FilterChip(
                    selected = blockSelected,
                    onClick = { onModeChange(ScheduleMode.BLOCK_DURING) },
                    label = {
                        Text(
                            if (blockSelected) "✓ 指定时段禁止" else "指定时段禁止",
                            fontWeight = if (blockSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFFE5E7EB),
                        labelColor = Color(0xFF6B7280),
                        selectedContainerColor = Color(0xFFB3261E),
                        selectedLabelColor = Color.White,
                    ),
                )
            }
            Text(
                if (mode == ScheduleMode.ALLOW_ONLY) {
                    "只有下列时段可以使用，其他时间打开会立即退出。"
                } else {
                    "下列时段不能使用，其他时间正常开放。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            windows.forEachIndexed { index, window ->
                ScheduleWindowEditor(
                    window = window,
                    onChange = { updated ->
                        onWindowsChange(windows.toMutableList().also { it[index] = updated })
                    },
                    onDelete = {
                        onWindowsChange(windows.toMutableList().also { it.removeAt(index) })
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    onWindowsChange(
                        windows + ScheduleWindow(
                            daysOfWeek = (1..7).toSet(),
                            startMinute = 8 * 60,
                            endMinute = 22 * 60,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("添加时段") }
            if (windows.isEmpty()) {
                Text(
                    "请至少添加一个时段。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleWindowEditor(
    window: ScheduleWindow,
    onChange: (ScheduleWindow) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F4F8)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("重复日期", fontWeight = FontWeight.Medium)
                TextButton(onClick = onDelete) { Text("删除") }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WEEKDAY_LABELS.forEachIndexed { index, label ->
                    val day = index + 1
                    val selected = day in window.daysOfWeek
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val days = window.daysOfWeek.toMutableSet()
                            if (!days.add(day)) days.remove(day)
                            onChange(window.copy(daysOfWeek = days))
                        },
                        label = {
                            Text(
                                if (selected) "✓$label" else label,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFFE2E4E8),
                            labelColor = Color(0xFF7A7F89),
                            selectedContainerColor = Color(0xFF315EA8),
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        showTimePicker(context, window.startMinute) { minute ->
                            onChange(window.copy(startMinute = minute))
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("开始 ${formatMinuteOfDay(window.startMinute)}") }
                OutlinedButton(
                    onClick = {
                        showTimePicker(context, window.endMinute) { minute ->
                            onChange(window.copy(endMinute = minute))
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("结束 ${formatMinuteOfDay(window.endMinute)}") }
            }
            when {
                window.startMinute == window.endMinute -> Text(
                    "开始和结束时间不能相同。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                window.daysOfWeek.isEmpty() -> Text(
                    "请至少选择一天。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                window.crossesMidnight -> Text(
                    "该时段将在次日 ${formatMinuteOfDay(window.endMinute)} 结束。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun showTimePicker(context: Context, initialMinute: Int, onSelected: (Int) -> Unit) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onSelected(hour * 60 + minute) },
        initialMinute / 60,
        initialMinute % 60,
        true,
    ).show()
}

private fun formatMinuteOfDay(minute: Int): String = String.format(
    java.util.Locale.ROOT,
    "%02d:%02d",
    minute / 60,
    minute % 60,
)

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
private fun ThresholdEditor(
    title: String,
    description: String,
    enabled: Boolean,
    minutes: String,
    parsedMinutes: Long?,
    onEnabledChange: (Boolean) -> Unit,
    onMinutesChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        TextField(
            value = minutes,
            onValueChange = { value -> onMinutesChange(value.filter(Char::isDigit).take(4)) },
            label = { Text("${title}时长（分钟）") },
            supportingText = { Text("范围 1–1440 分钟") },
            isError = enabled && minutes.isNotEmpty() && parsedMinutes == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatDuration(seconds: Long): String =
    if (seconds < 60L) "$seconds 秒" else "${seconds / 60L} 分钟"

private fun ruleSummary(rule: AppRule): String = buildList {
    if (rule.dailyEnabled) add("每日 ${formatDuration(rule.dailyLimitSeconds)}")
    if (rule.perLaunchEnabled) add("单次 ${formatDuration(rule.perLaunchLimitSeconds)}")
    if (rule.scheduleEnabled) {
        add(if (rule.scheduleMode == ScheduleMode.ALLOW_ONLY) "限定允许时段" else "设有禁止时段")
    }
    if (rule.cooldownEnabled) add("冷却 ${formatDuration(rule.cooldownSeconds)}")
}.joinToString(" · ")

@Composable
private fun AppTimeLimiterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF315EA8),
            secondary = Color(0xFF536F9E),
            surface = Color.White,
            background = Color(0xFFF7F8FC),
        ),
        content = content,
    )
}
