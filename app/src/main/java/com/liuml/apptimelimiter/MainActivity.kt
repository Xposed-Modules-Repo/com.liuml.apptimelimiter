package com.liuml.apptimelimiter

import android.content.Context
import android.app.Activity
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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Text as MaterialText
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppLanguageMode
import com.liuml.apptimelimiter.data.GlobalSettings
import com.liuml.apptimelimiter.data.InstalledApp
import com.liuml.apptimelimiter.data.InstalledAppsRepository
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.ScheduleCodec
import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.data.ScheduleWindow
import com.liuml.apptimelimiter.core.GroupUsagePolicy
import com.liuml.apptimelimiter.localization.AppLocaleController
import com.liuml.apptimelimiter.localization.SupportedLanguage
import com.liuml.apptimelimiter.localization.UiText
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val languageMode = runCatching {
            RuleRepository(newBase).getGlobalSettings().languageMode
        }.getOrDefault(AppLanguageMode.SYSTEM)
        super.attachBaseContext(AppLocaleController.wrap(newBase, languageMode))
    }

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
    var showFeedbackOptions by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var selectedSection by remember { mutableStateOf(MainSection.HOME) }
    var groups by remember { mutableStateOf(repository.getGroups()) }
    var editingGroup by remember { mutableStateOf<AppGroup?>(null) }
    var scopeReminderPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var usageRevision by remember { mutableIntStateOf(0) }
    var showFeatureIntro by remember {
        mutableStateOf(
            !context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getBoolean(FEATURE_INTRO_DISABLED_KEY, false),
        )
    }
    var showSetupGuide by remember { mutableStateOf(false) }

    val groupByPackage = remember(groups) {
        groups.filter(AppGroup::enabled)
            .flatMap { group -> group.packageNames.map { it to group } }
            .toMap()
    }
    val selectableApps = remember(apps, showSystemApps) {
        if (showSystemApps) apps else apps.filterNot(InstalledApp::isSystemApp)
    }
    val visibleApps = remember(apps, search, onlyEnabled, showSystemApps, rules.toMap(), groupByPackage) {
        apps.filter { app ->
            (showSystemApps || !app.isSystemApp) &&
            (!onlyEnabled || rules[app.packageName]?.let {
                it.enabled || it.sessionPlanningEnabled
            } == true || app.packageName in groupByPackage) &&
                (search.isBlank() || app.label.contains(search, true) || app.packageName.contains(search, true))
        }.sortedWith(
            compareByDescending<InstalledApp> {
                rules[it.packageName]?.let { rule ->
                    rule.enabled || rule.sessionPlanningEnabled
                } == true || it.packageName in groupByPackage
            }
                .thenBy { it.label.lowercase() },
        )
    }
    val enabledPackages = remember(rules.toMap(), groupByPackage) {
        rules.values.filter { it.enabled || it.sessionPlanningEnabled }
            .map(AppRule::packageName)
            .toSet() + groupByPackage.keys
    }
    val hookFeaturePackages = remember(rules.toMap(), groupByPackage) {
        rules.values.filter { it.enabled || it.sessionPlanningEnabled }
            .map(AppRule::packageName)
            .toSet() + groupByPackage.keys
    }
    val statsEnabled = remember(usageRevision) {
        repository.getGlobalSettings().usageStatsEnabled
    }
    val usageAccessGranted = remember(usageRevision) {
        deviceUsageStatsRepository.hasUsageAccess()
    }
    val allLaunchablePackages = remember(apps) { apps.map(InstalledApp::packageName).toSet() }
    val groupPackages = remember(groups) { groups.flatMapTo(mutableSetOf(), AppGroup::packageNames) }
    val hookSummaries = remember(allLaunchablePackages, usageRevision) {
        usageStatsRepository.summariesToday(allLaunchablePackages)
    }
    val systemTrackedPackages = remember(allLaunchablePackages, groupPackages, statsEnabled) {
        if (statsEnabled) allLaunchablePackages else groupPackages
    }
    var systemSummaries by remember {
        mutableStateOf<Map<String, CalculatedUsageSummary>>(emptyMap())
    }
    LaunchedEffect(systemTrackedPackages, usageRevision, usageAccessGranted) {
        systemSummaries = if (usageAccessGranted && systemTrackedPackages.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                deviceUsageStatsRepository.todayUsageSummaries(systemTrackedPackages)
            }
        } else {
            emptyMap()
        }
    }
    val todaySummaries = remember(
        hookSummaries,
        systemSummaries,
        usageAccessGranted,
        statsEnabled,
    ) {
        if (!statsEnabled) return@remember emptyList()
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
                durationMillis = maxOf(
                    hook.durationMillis,
                    if (usageAccessGranted) system?.durationMillis ?: 0L else 0L,
                ),
                launchCount = system?.launchCount ?: hook.launchCount,
                lastUsedAtMillis = maxOf(hook.lastUsedAtMillis, system?.lastUsedAtMillis ?: 0L),
            )
                .takeIf { it.durationMillis > 0L || it.launchCount > 0 || it.limitHitCount > 0 }
        }
    }
    val todayTotalMillis = todaySummaries.sumOf(AppUsageSummary::durationMillis)
    val groupUsageById = remember(groups, hookSummaries, systemSummaries) {
        val moduleDurations = hookSummaries.associate { it.packageName to it.durationMillis }
        val systemDurations = systemSummaries.mapValues { it.value.durationMillis }
        groups.associate { group ->
            group.id to GroupUsagePolicy.authoritativeTotalMillis(
                packageNames = group.packageNames,
                systemDurations = systemDurations,
                moduleDurations = moduleDurations,
            )
        }
    }
    val verifiedHookPackages = remember(hookFeaturePackages, usageRevision) {
        usageStatsRepository.verifiedHookPackages(hookFeaturePackages, BuildConfig.VERSION_CODE)
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
            selectedSection == MainSection.GROUPS ||
            (
                selectedSection == MainSection.STATS &&
                    statsEnabled &&
                    usageAccessGranted
                )
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
                                MainSection.GROUPS -> "应用分组"
                                MainSection.STATS -> "使用统计"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when (selectedSection) {
                                MainSection.HOME -> "应用使用时长管控"
                                MainSection.APPS -> "已启用 ${enabledPackages.size} 个应用"
                                MainSection.GROUPS -> "${groups.size} 个应用分组"
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
                                    MainSection.GROUPS -> "◫"
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
                verifiedHookPackages = verifiedHookPackages,
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
                        groupName = groupByPackage[app.packageName]?.name,
                        hookVerified = app.packageName in verifiedHookPackages,
                        onClick = { editingApp = app },
                        onToggle = { enabled ->
                            if (enabled) {
                                editingApp = app
                            } else {
                                val updated = rule.copy(
                                    enabled = false,
                                    sessionPlanningEnabled = false,
                                )
                                if (!repository.save(updated)) {
                                    Toast.makeText(
                                        context,
                                        localizedText(
                                            context,
                                            "规则保存失败，请重试",
                                            "Failed to save the rule. Try again.",
                                        ),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    return@AppRow
                                }
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

            MainSection.GROUPS -> GroupManagementScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                groups = groups,
                apps = apps,
                usageByGroupId = groupUsageById,
                onAdd = {
                    editingGroup = AppGroup(
                        id = repository.newGroupId(),
                        name = "新分组",
                    )
                },
                onEdit = { editingGroup = it },
            )

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
            onSave = saveRule@{ configuredRule ->
                val newlyControlled = (configuredRule.enabled || configuredRule.sessionPlanningEnabled) &&
                    app.packageName !in hookFeaturePackages
                if (!repository.save(configuredRule)) {
                    Toast.makeText(
                        context,
                        localizedText(
                            context,
                            "规则保存失败，请重试",
                            "Failed to save the rule. Try again.",
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@saveRule
                }
                if (repository.getGlobalSettings().diagnosticsEnabled) {
                    diagnosticsRepository.append(
                        "INFO",
                        app.packageName,
                        "RULE_SAVED",
                        "enabled=${configuredRule.enabled}, sessionPlanning=${configuredRule.sessionPlanningEnabled}, daily=${configuredRule.dailyEnabled}/${configuredRule.dailyLimitSeconds}s, perLaunch=${configuredRule.perLaunchEnabled}/${configuredRule.perLaunchLimitSeconds}s, schedule=${configuredRule.scheduleEnabled}/${configuredRule.scheduleMode}/${configuredRule.scheduleWindows.size}, cooldown=${configuredRule.cooldownEnabled}/${configuredRule.cooldownSeconds}s",
                    )
                }
                rules[app.packageName] = repository.getRule(app.packageName)
                if (
                    newlyControlled &&
                    app.packageName !in verifiedHookPackages
                ) {
                    scopeReminderPackages = setOf(app.packageName)
                }
                editingApp = null
            },
        )
    }

    editingGroup?.let { group ->
        GroupEditorDialog(
            initialGroup = group,
            apps = selectableApps,
            groups = groups,
            onDismiss = { editingGroup = null },
            onSave = { updated ->
                val newlyControlledPackages = if (updated.enabled) {
                    updated.packageNames - enabledPackages
                } else {
                    emptySet()
                }
                if (repository.saveGroup(updated)) {
                    groups = repository.getGroups()
                    scopeReminderPackages = newlyControlledPackages - verifiedHookPackages
                    editingGroup = null
                    usageRevision++
                } else {
                    Toast.makeText(
                        context,
                        localizedText(
                            context,
                            "保存失败：应用可能已属于其他分组",
                            "Save failed: an app may already belong to another group",
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onDelete = {
                if (repository.deleteGroup(group.id)) {
                    groups = repository.getGroups()
                    editingGroup = null
                    usageRevision++
                } else {
                    Toast.makeText(
                        context,
                        localizedText(context, "删除分组失败", "Failed to delete group"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
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
                UpdateChecker.check(context) { result ->
                    checkingUpdate = false
                    updateResult = result
                }
            },
            onAbout = {
                showSettings = false
                showAbout = true
            },
            onJoinBeta = { openQqGroup(context) },
            onFeedback = {
                showSettings = false
                showFeedbackOptions = true
            },
            onCopyRecoveryCommand = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText("恢复应用入口", LauncherIconController.RECOVERY_COMMAND),
                )
                Toast.makeText(
                    context,
                    localizedText(context, "恢复命令已复制", "Recovery command copied"),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onRequestUsageAccess = deviceUsageStatsRepository::openUsageAccessSettings,
            onSave = saveSettings@{ settings ->
                val previousSettings = repository.getGlobalSettings()
                val iconResult = runCatching {
                    LauncherIconController.setHidden(context, settings.launcherIconHidden)
                }
                if (iconResult.isFailure) {
                    Toast.makeText(
                        context,
                        localizedText(
                            context,
                            "修改桌面图标失败：${iconResult.exceptionOrNull()?.message}",
                            "Failed to change launcher icon: ${iconResult.exceptionOrNull()?.message}",
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@saveSettings
                }
                if (!repository.saveGlobalSettings(settings)) {
                    runCatching {
                        LauncherIconController.setHidden(
                            context,
                            previousSettings.launcherIconHidden,
                        )
                    }
                    Toast.makeText(
                        context,
                        localizedText(
                            context,
                            "设置保存失败，请重试",
                            "Failed to save settings. Try again.",
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@saveSettings
                }
                if (settings.diagnosticsEnabled) {
                    diagnosticsRepository.append(
                        "INFO",
                        context.packageName,
                        "SETTINGS_SAVED",
                        "warning=${settings.exitWarningEnabled}, fullScreen=${settings.fullScreenExitWarningEnabled}, vibration=${settings.exitWarningVibrationEnabled}, language=${settings.languageMode}, extension=${settings.extensionSeconds}s, diagnostics=${settings.diagnosticsEnabled}, iconHidden=${settings.launcherIconHidden}, usageStats=${settings.usageStatsEnabled}",
                    )
                }
                if (settings.launcherIconHidden) {
                    Toast.makeText(
                        context,
                        localizedText(
                            context,
                            "桌面图标已关闭，可从 LSPosed 模块页打开应用",
                            "Launcher icon hidden; open the app from the LSPosed module page",
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                usageRevision++
                showSettings = false
                if (settings.languageMode != previousSettings.languageMode) {
                    val manualRecreate = AppLocaleController.apply(context, settings.languageMode)
                    if (manualRecreate) (context as? Activity)?.recreate()
                }
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
                            localizedText(
                                context,
                                "已加入系统下载队列；完成后点击系统通知安装",
                                "Added to the download queue; tap the system notification to install when complete",
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                        updateResult = null
                    }
                    .onFailure {
                        Toast.makeText(
                            context,
                            localizedText(
                                context,
                                "下载失败：${it.message}",
                                "Download failed: ${it.message}",
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            },
            onOpenPage = { release -> openUrl(context, release.pageUrl) },
        )
    }

    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onOpenRepository = { openUrl(context, UpdateChecker.REPOSITORY_URL) },
            onFeedback = {
                showAbout = false
                showFeedbackOptions = true
            },
            onJoinQqGroup = { openQqGroup(context) },
        )
    }

    if (showFeedbackOptions) {
        FeedbackOptionsDialog(
            onDismiss = { showFeedbackOptions = false },
            onEmail = {
                showFeedbackOptions = false
                FeedbackSender.send(context, diagnosticsRepository)
            },
            onQqGroup = {
                showFeedbackOptions = false
                openQqGroup(context)
            },
        )
    }

    if (scopeReminderPackages.isNotEmpty()) {
        val appByPackage = remember(apps) { apps.associateBy(InstalledApp::packageName) }
        val labels = scopeReminderPackages.map { appByPackage[it]?.label ?: it }.sorted()
        AlertDialog(
            onDismissRequest = { scopeReminderPackages = emptySet() },
            title = { Text("请确认 LSPosed 作用域") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(labels.joinToString("、"))
                    Text(
                        "规则已保存，但这些应用尚未回传当前版本的 Hook 验证。请确认已加入时停的 LSPosed 作用域，再强制停止并重新打开目标应用。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { scopeReminderPackages = emptySet() }) { Text("我已了解") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scopeReminderPackages = emptySet()
                    showSetupGuide = true
                }) { Text("查看配置要求") }
            },
        )
    }

    if (showFeatureIntro) {
        FeatureIntroDialog(
            onClose = { doNotShowAgain ->
                if (doNotShowAgain) {
                    context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(FEATURE_INTRO_DISABLED_KEY, true)
                        .apply()
                }
                showFeatureIntro = false
            },
        )
    } else if (showSetupGuide) {
        SetupGuideDialog(
            onDismiss = {
                showSetupGuide = false
            },
        )
    }
}

private enum class MainSection(val label: String) {
    HOME("首页"),
    APPS("应用"),
    GROUPS("分组"),
    STATS("统计"),
}

@Composable
private fun HomeDashboard(
    modifier: Modifier,
    enabledCount: Int,
    todayTotalMillis: Long,
    verifiedHookPackages: Set<String>,
    statsEnabled: Boolean,
    usageAccessGranted: Boolean,
    onRequestUsageAccess: () -> Unit,
    onManageApps: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val verifiedCount = verifiedHookPackages.size.coerceAtMost(enabledCount)
    val pendingCount = (enabledCount - verifiedCount).coerceAtLeast(0)
    val allVerified = enabledCount > 0 && pendingCount == 0
    val statusTitle = when {
        enabledCount == 0 -> "未启用管控"
        allVerified -> "Hook 已验证"
        verifiedCount > 0 -> "已验证 $verifiedCount / $enabledCount 个应用"
        else -> "等待 Hook 验证"
    }
    val statusDescription = when {
        enabledCount == 0 -> "请先选择需要管控的应用"
        allVerified -> "$enabledCount 个管控应用均已回传当前版本 Hook 记录"
        else -> "仍有 $pendingCount 个应用待验证；请确认作用域后强停并重开它们"
    }
    val healthy = allVerified
    val warning = enabledCount > 0 && !allVerified
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
                    containerColor = when {
                        healthy -> Color(0xFFE0F4E8)
                        warning -> Color(0xFFFFE9C7)
                        else -> Color(0xFFE9DDFB)
                    },
                ),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = when {
                            healthy -> Color(0xFF19734A)
                            warning -> Color(0xFFB54708)
                            else -> Color(0xFF351078)
                        },
                        shape = CircleShape,
                    ) {
                        Text(
                            if (healthy) "✓" else if (warning) "!" else "◆",
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
                        "使用统计展示已关闭；应用分组仍会保留共享额度所需的内部时长。",
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
private fun GroupManagementScreen(
    modifier: Modifier,
    groups: List<AppGroup>,
    apps: List<InstalledApp>,
    usageByGroupId: Map<String, Long>,
    onAdd: () -> Unit,
    onEdit: (AppGroup) -> Unit,
) {
    val appByPackage = remember(apps) { apps.associateBy(InstalledApp::packageName) }
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(color = Color(0xFFE8F0FF), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("分组额度与规则", fontWeight = FontWeight.Bold, color = Color(0xFF154B99))
                    Text(
                        "可统一设置共享每日额度、单次打开、可用时段和退出后冷却；应用自己的规则仍会同时生效。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF294D7A),
                    )
                    Button(onClick = onAdd) { Text("新建分组") }
                }
            }
        }
        if (groups.isEmpty()) {
            item {
                Text(
                    "暂无分组。可以把短视频、游戏等应用放入同一组共享额度。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(groups, key = AppGroup::id) { group ->
            val usedMillis = usageByGroupId[group.id] ?: 0L
            val limitMillis = group.dailyLimitSeconds * 1000L
            val remainingMillis = (limitMillis - usedMillis).coerceAtLeast(0L)
            val ruleLabels = buildList {
                if (group.dailyEnabled) {
                    add("每日 ${formatDuration(group.dailyLimitSeconds)}")
                }
                if (group.perLaunchEnabled) {
                    add("单次 ${formatDuration(group.perLaunchLimitSeconds)}")
                }
                if (group.scheduleEnabled) add("可用时段")
                if (group.cooldownEnabled) {
                    add("冷却 ${formatDuration(group.cooldownSeconds)}")
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onEdit(group) },
                color = if (group.enabled) Color(0xFFF2F6FF) else Color.White,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(group.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        ManagedBadge(if (group.enabled) "管控中" else "已停用")
                    }
                    if (group.dailyEnabled) {
                        Text(
                            "共享每日：已用 ${formatDashboardDuration(usedMillis)} / ${formatDashboardDuration(limitMillis)} · 剩余 ${formatDashboardDuration(remainingMillis)}",
                            color = if (remainingMillis == 0L && group.enabled) Color(0xFFB42318)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        ruleLabels.joinToString(" · ").ifBlank { "未启用规则" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${group.packageNames.size} 个应用：" + group.packageNames
                            .map { appByPackage[it]?.label ?: it }
                            .sorted()
                            .take(5)
                            .joinToString("、") +
                            if (group.packageNames.size > 5) " 等" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupEditorDialog(
    initialGroup: AppGroup,
    apps: List<InstalledApp>,
    groups: List<AppGroup>,
    onDismiss: () -> Unit,
    onSave: (AppGroup) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(initialGroup.id, initialGroup.version) { mutableStateOf(initialGroup.name) }
    var enabled by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf(initialGroup.enabled)
    }
    var dailyEnabled by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf(initialGroup.dailyEnabled)
    }
    var dailyMinutes by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf((initialGroup.dailyLimitSeconds / 60L).coerceAtLeast(1L).toString())
    }
    var perLaunchEnabled by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf(initialGroup.perLaunchEnabled)
    }
    var perLaunchMinutes by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf((initialGroup.perLaunchLimitSeconds / 60L).coerceAtLeast(1L).toString())
    }
    var scheduleEnabled by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf(initialGroup.scheduleEnabled)
    }
    var scheduleMode by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf(initialGroup.scheduleMode)
    }
    var scheduleWindows by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf(initialGroup.scheduleWindows)
    }
    var cooldownEnabled by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf(initialGroup.cooldownEnabled)
    }
    var cooldownMinutes by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf((initialGroup.cooldownSeconds / 60L).coerceAtLeast(1L).toString())
    }
    var selectedPackages by remember(initialGroup.id, initialGroup.version) {
        mutableStateOf(initialGroup.packageNames)
    }
    var appSearch by remember(initialGroup.id) { mutableStateOf("") }
    var confirmDelete by remember(initialGroup.id) { mutableStateOf(false) }
    val occupiedByOtherGroup = remember(groups, initialGroup.id) {
        groups.filterNot { it.id == initialGroup.id }
            .flatMap { group -> group.packageNames.map { it to group.name } }
            .toMap()
    }
    val parsedDailyMinutes = dailyMinutes.toLongOrNull()?.takeIf { it in 1L..1_440L }
    val parsedPerLaunchMinutes = perLaunchMinutes.toLongOrNull()?.takeIf { it in 1L..1_440L }
    val parsedCooldownMinutes = cooldownMinutes.toLongOrNull()?.takeIf { it in 1L..1_440L }
    val scheduleValid = !scheduleEnabled ||
        (scheduleWindows.isNotEmpty() && scheduleWindows.all(ScheduleWindow::isValid))
    val hasRule = dailyEnabled || perLaunchEnabled || scheduleEnabled || cooldownEnabled
    val canSave = name.trim().isNotEmpty() &&
        selectedPackages.isNotEmpty() &&
        hasRule &&
        (!dailyEnabled || parsedDailyMinutes != null) &&
        (!perLaunchEnabled || parsedPerLaunchMinutes != null) &&
        (!cooldownEnabled || parsedCooldownMinutes != null) &&
        scheduleValid
    val existing = groups.any { it.id == initialGroup.id }
    val matchingApps = remember(apps, appSearch, selectedPackages) {
        val keyword = appSearch.trim()
        apps.filter { app ->
            keyword.isEmpty() ||
                app.label.contains(keyword, ignoreCase = true) ||
                app.packageName.contains(keyword, ignoreCase = true)
        }.sortedWith(
            compareByDescending<InstalledApp> { it.packageName in selectedPackages }
                .thenBy { it.label.lowercase() },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing) "编辑应用分组" else "新建应用分组") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    TextField(
                        value = name,
                        onValueChange = { name = it.take(RuleRepository.MAX_GROUP_NAME_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("分组名称") },
                        singleLine = true,
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("启用分组管控", fontWeight = FontWeight.Medium)
                            Text(
                                "关闭后保留成员和规则配置，但暂不执行",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
                item { HorizontalDivider() }
                item {
                    Text(
                        "分组规则",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    ThresholdEditor(
                        title = "每日累计",
                        description = "组内成员共同消耗一个每日额度",
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
                        description = "统一限制每个成员应用的单次前台使用时长",
                        enabled = perLaunchEnabled,
                        minutes = perLaunchMinutes,
                        parsedMinutes = parsedPerLaunchMinutes,
                        onEnabledChange = { perLaunchEnabled = it },
                        onMinutesChange = { perLaunchMinutes = it },
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
                        description = "成员被强制退出后，在设定时间内禁止其再次打开",
                        enabled = cooldownEnabled,
                        minutes = cooldownMinutes,
                        parsedMinutes = parsedCooldownMinutes,
                        onEnabledChange = { cooldownEnabled = it },
                        onMinutesChange = { cooldownMinutes = it },
                    )
                }
                item {
                    Text(
                        "应用自己的规则与分组规则同时计算，任一先触发即执行退出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item { HorizontalDivider() }
                item {
                    Text("选择应用（${selectedPackages.size}）", fontWeight = FontWeight.Medium)
                    Text(
                        "已选应用会自动置顶。每个应用只能属于一个组；所有成员仍需在 LSPosed 作用域中勾选。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    TextField(
                        value = appSearch,
                        onValueChange = { appSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索应用名或包名") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                if (matchingApps.isEmpty()) {
                    item {
                        Text(
                            "没有匹配的应用",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(matchingApps, key = InstalledApp::packageName) { app ->
                    val occupiedBy = occupiedByOtherGroup[app.packageName]
                    val selected = app.packageName in selectedPackages
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedPackages = if (selected) {
                                selectedPackages - app.packageName
                            } else {
                                selectedPackages + app.packageName
                            }
                        },
                        enabled = occupiedBy == null,
                        label = {
                            Text(
                                if (occupiedBy == null) app.label else "${app.label}（已在 $occupiedBy）",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initialGroup.copy(
                            name = name.trim(),
                            enabled = enabled,
                            dailyEnabled = dailyEnabled,
                            dailyLimitSeconds = (parsedDailyMinutes ?: 1L) * 60L,
                            perLaunchEnabled = perLaunchEnabled,
                            perLaunchLimitSeconds = (parsedPerLaunchMinutes ?: 1L) * 60L,
                            scheduleEnabled = scheduleEnabled,
                            scheduleMode = scheduleMode,
                            scheduleWindows = scheduleWindows,
                            cooldownEnabled = cooldownEnabled,
                            cooldownSeconds = (parsedCooldownMinutes ?: 1L) * 60L,
                            packageNames = selectedPackages,
                        ),
                    )
                },
                enabled = canSave,
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (existing) TextButton(onClick = { confirmDelete = true }) { Text("删除分组") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${initialGroup.name}？") },
            text = { Text("只会解除分组规则，不会删除应用原有的独立规则。") },
            confirmButton = {
                Button(onClick = onDelete) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    rule: AppRule,
    groupName: String?,
    hookVerified: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val controlled = rule.enabled || rule.sessionPlanningEnabled || groupName != null
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (controlled) Color(0xFFF2F6FF) else Color.White,
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
                color = if (controlled) Color(0xFF315EA8) else Color(0xFFE6E8EE),
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
                    if (rule.sessionPlanningEnabled) ManagedBadge("计划")
                    if (groupName != null) ManagedBadge("分组")
                    if (controlled && !hookVerified) PendingHookBadge()
                }
                Text(
                    if (controlled) {
                        listOfNotNull(
                            ruleSummary(rule).takeIf { rule.enabled },
                            "打开时制定计划".takeIf { rule.sessionPlanningEnabled },
                            groupName?.let { "$it · 共享额度" },
                        ).joinToString(" · ")
                    } else {
                        app.packageName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = rule.enabled || rule.sessionPlanningEnabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
private fun PendingHookBadge() {
    Surface(
        color = Color(0xFFB54708),
        contentColor = Color.White,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            "待 Hook 验证",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ManagedBadge(label: String = "管控中") {
    Surface(
        color = Color(0xFF315EA8),
        contentColor = Color.White,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AppIcon(app: InstalledApp) {
    val context = LocalContext.current
    val language = currentSupportedLanguage()
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
            contentDescription = UiText.translate("${app.label}图标", language),
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

private data class FeatureIntroPage(
    val eyebrow: String,
    val title: String,
    val description: String,
    val highlights: List<String>,
)

@Composable
private fun FeatureIntroDialog(onClose: (doNotShowAgain: Boolean) -> Unit) {
    val context = LocalContext.current
    val pages = remember {
        listOf(
            FeatureIntroPage(
                eyebrow = "精细管控",
                title = "把使用边界设清楚",
                description = "每日累计、单次打开、可用时段和退出后冷却可以独立开启，也可以组合生效。",
                highlights = listOf("任一规则先到即执行退出", "管理应用被清理后规则仍由 Hook 执行"),
            ),
            FeatureIntroPage(
                eyebrow = "本次计划",
                title = "打开应用前，先决定用多久",
                description = "为应用开启“打开时制定计划”，每次新进程首次进入时选择本次前台使用时长。",
                highlights = listOf("后台和息屏期间暂停计时", "计划不能超过现有应用或分组剩余额度"),
            ),
            FeatureIntroPage(
                eyebrow = "应用分组",
                title = "一组应用，共享一套规则",
                description = "将短视频、游戏等应用归入同一组，统一配置共享每日额度、单次打开、时段和冷却。",
                highlights = listOf("已选应用自动置顶，便于维护", "应用独立规则与分组规则同时生效"),
            ),
            FeatureIntroPage(
                eyebrow = "开始之前",
                title = "确认 LSPosed 作用域",
                description = "启用时停模块后，需要把每个受管控应用加入模块作用域，并强制停止后重新打开。",
                highlights = listOf("统计页按需读取系统使用时长", "诊断日志可检查 HOOK_READY 与限制事件"),
            ),
        )
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    var doNotShowAgain by remember { mutableStateOf(false) }
    val lastPage = pagerState.currentPage == pages.lastIndex

    AlertDialog(
        onDismissRequest = { onClose(doNotShowAgain) },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("欢迎使用时停")
                Text(
                    "${pagerState.currentPage + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                HorizontalPager(
                    state = pagerState,
                    pageSpacing = 16.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) { pageIndex ->
                    val page = pages[pageIndex]
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                page.eyebrow,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                page.title,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                page.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            page.highlights.forEach { highlight ->
                                Text(
                                    "• $highlight",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    pages.indices.forEach { index ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (index == pagerState.currentPage) 10.dp else 7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == pagerState.currentPage) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { doNotShowAgain = !doNotShowAgain },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = doNotShowAgain,
                        onCheckedChange = { doNotShowAgain = it },
                    )
                    Text(
                        localizedText(
                            context,
                            "不再显示功能介绍",
                            "Do not show this introduction again",
                        ),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (lastPage) {
                        onClose(doNotShowAgain)
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
            ) {
                Text(if (lastPage) "开始使用" else "下一页")
            }
        },
        dismissButton = {
            Row {
                if (pagerState.currentPage > 0) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                    ) { Text("上一页") }
                }
                TextButton(onClick = { onClose(doNotShowAgain) }) { Text("稍后再看") }
            }
        },
    )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsDialog(
    initialSettings: GlobalSettings,
    usageAccessGranted: Boolean,
    onDismiss: () -> Unit,
    onDonate: () -> Unit,
    onCheckUpdates: () -> Unit,
    onAbout: () -> Unit,
    onJoinBeta: () -> Unit,
    onFeedback: () -> Unit,
    onCopyRecoveryCommand: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onSave: (GlobalSettings) -> Unit,
) {
    var warningEnabled by remember { mutableStateOf(initialSettings.exitWarningEnabled) }
    var fullScreenWarningEnabled by remember {
        mutableStateOf(initialSettings.fullScreenExitWarningEnabled)
    }
    var vibrationEnabled by remember {
        mutableStateOf(initialSettings.exitWarningVibrationEnabled)
    }
    var languageMode by remember { mutableStateOf(initialSettings.languageMode) }
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
                    SettingsSectionTitle("提醒与延时")
                }
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
                        Switch(
                            checked = warningEnabled,
                            onCheckedChange = { warningEnabled = it },
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("全屏退出提醒", fontWeight = FontWeight.Medium)
                            Text(
                                "开启后倒计时覆盖当前应用；关闭时显示顶部圆角提醒",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = fullScreenWarningEnabled,
                            onCheckedChange = { fullScreenWarningEnabled = it },
                            enabled = warningEnabled,
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("长震动提醒", fontWeight = FontWeight.Medium)
                            Text(
                                "退出倒计时出现时震动一次",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = vibrationEnabled,
                            onCheckedChange = { vibrationEnabled = it },
                            enabled = warningEnabled,
                        )
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
                    SettingsSectionTitle("统计与诊断")
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
                                "统计页按需读取系统数据；共享额度会保留必要的内部计时",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = usageStatsEnabled,
                            onCheckedChange = { usageStatsEnabled = it },
                        )
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("诊断日志", fontWeight = FontWeight.Medium)
                            Text("记录 Hook、计时、延时和退出事件", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = diagnosticsEnabled,
                            onCheckedChange = { diagnosticsEnabled = it },
                        )
                    }
                }
                item { HorizontalDivider() }
                item {
                    SettingsSectionTitle("应用设置")
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("语言", fontWeight = FontWeight.Medium)
                        Text("默认跟随系统语言", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppLanguageMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = languageMode == mode,
                                    onClick = { languageMode = mode },
                                    label = {
                                        Text(
                                            when (mode) {
                                                AppLanguageMode.SYSTEM -> "跟随系统"
                                                AppLanguageMode.SIMPLIFIED_CHINESE -> "简体中文"
                                                AppLanguageMode.ENGLISH -> "English"
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
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
                    SettingsSectionTitle("维护与支持")
                }
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
                        title = "加入内测",
                        description = "加入 QQ 群获取测试版本并反馈问题",
                        action = "加群 ›",
                        onClick = onJoinBeta,
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
                item { HorizontalDivider() }
                item {
                    SettingsEntry(
                        title = "支持开发",
                        description = "支付宝捐赠：$DONATION_ACCOUNT",
                        action = "去转账 ›",
                        onClick = onDonate,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initialSettings.copy(
                            exitWarningEnabled = warningEnabled,
                            fullScreenExitWarningEnabled = fullScreenWarningEnabled,
                            exitWarningVibrationEnabled = vibrationEnabled,
                            languageMode = languageMode,
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
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleSmall,
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
private fun FeedbackOptionsDialog(
    onDismiss: () -> Unit,
    onEmail: () -> Unit,
    onQqGroup: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("反馈问题") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "请选择反馈方式。邮件反馈会附带设备信息和诊断日志，QQ群适合交流和参与内测。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onEmail,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("邮件反馈")
                }
                OutlinedButton(
                    onClick = onQqGroup,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("QQ群反馈")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenRepository: () -> Unit,
    onFeedback: () -> Unit,
    onJoinQqGroup: () -> Unit,
) {
    var showSoftwareNotice by remember { mutableStateOf(false) }
    if (showSoftwareNotice) {
        SoftwareNoticeDialog(onDismiss = { showSoftwareNotice = false })
        return
    }

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
                TextButton(onClick = onJoinQqGroup) { Text("加入 QQ 群：$QQ_GROUP_NUMBER") }
                TextButton(onClick = onFeedback) { Text("发送问题反馈") }
                TextButton(onClick = { showSoftwareNotice = true }) { Text("查看软件声明") }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SoftwareNoticeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("软件声明") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("时停项目原创内容保留全部权利。公开可见不代表授予复制、修改、再发布或商业销售许可。")
                Text("未经项目作者书面授权，不得抄袭、改名冒充、打包倒卖、收费分发或将本软件用于其他商业产品。")
                Text("第三方开源组件仍分别遵循其原有许可证；法律规定的合理使用及其他法定权利不受本声明限制。")
                Text(
                    "完整条款见项目仓库根目录 LICENSE 文件。",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("我已了解") } },
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure {
            Toast.makeText(
                context,
                localizedText(context, "无法打开链接", "Unable to open link"),
                Toast.LENGTH_SHORT,
            ).show()
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
                localizedText(
                    context,
                    "支付宝账号已复制：$DONATION_ACCOUNT，请在转账前核对账号",
                    "Alipay account copied: $DONATION_ACCOUNT. Verify it before transferring.",
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
}

private fun openQqGroup(context: Context) {
    val groupUri = Uri.parse(
        "mqqapi://card/show_pslcard" +
            "?src_type=internal&version=1&uin=$QQ_GROUP_NUMBER&card_type=group&source=qrcode",
    )
    val opened = QQ_PACKAGES.any { packageName ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, groupUri)
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }
    if (opened) return

    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
        ClipData.newPlainText("时停 QQ 群", QQ_GROUP_NUMBER),
    )
    QQ_PACKAGES.firstNotNullOfOrNull { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { launchIntent ->
        runCatching { context.startActivity(launchIntent) }
    }
    Toast.makeText(
        context,
        localizedText(
            context,
            "无法直接打开群资料，群号已复制：$QQ_GROUP_NUMBER",
            "Could not open the group directly. Group number copied: $QQ_GROUP_NUMBER",
        ),
        Toast.LENGTH_LONG,
    ).show()
}

private const val DONATION_ACCOUNT = "liuml.yx@139.com"
private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
private const val QQ_GROUP_NUMBER = "1009712674"
private val QQ_PACKAGES = listOf("com.tencent.mobileqq", "com.tencent.tim")
private const val FEATURE_INTRO_DISABLED_KEY = "feature_intro_disabled"

private fun localizedText(context: Context, chinese: String, english: String): String {
    val mode = RuleRepository(context).getGlobalSettings().languageMode
    return if (AppLocaleController.resolvedLanguage(context, mode) == SupportedLanguage.ENGLISH) {
        english
    } else {
        chinese
    }
}

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
    var sessionPlanningEnabled by remember(app.packageName) {
        mutableStateOf(initialRule.sessionPlanningEnabled)
    }
    var cooldownMinutes by remember(app.packageName) {
        mutableStateOf((initialRule.cooldownSeconds / 60L).coerceAtLeast(1L).toString())
    }
    val parsedDailyMinutes = dailyMinutes.toLongOrNull()?.takeIf { it in 1..1440 }
    val parsedPerLaunchMinutes = perLaunchMinutes.toLongOrNull()?.takeIf { it in 1..1440 }
    val parsedCooldownMinutes = cooldownMinutes.toLongOrNull()?.takeIf { it in 1..1440 }
    val scheduleValid = !scheduleEnabled ||
        (scheduleWindows.isNotEmpty() && scheduleWindows.all(ScheduleWindow::isValid))
    val canSave = (dailyEnabled || perLaunchEnabled || scheduleEnabled || sessionPlanningEnabled) &&
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
                    Text(
                        "固定管控规则",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
                item { HorizontalDivider() }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("打开时制定计划", fontWeight = FontWeight.Medium)
                            Text(
                                "可选功能：每次目标应用进程启动后，先选择本次计划使用时长",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = sessionPlanningEnabled,
                            onCheckedChange = { sessionPlanningEnabled = it },
                        )
                    }
                    if (sessionPlanningEnabled) {
                        Text(
                            if (app.isSystemApp) {
                                "计划到期时只能关闭应用界面，不结束系统进程。"
                            } else {
                                "计划只计算前台时间；可跳过，也可在退出前重新制定。"
                            },
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
                            enabled = dailyEnabled || perLaunchEnabled || scheduleEnabled,
                            sessionPlanningEnabled = sessionPlanningEnabled,
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
                Text("保存设置")
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
                enabled = windows.size < ScheduleCodec.MAX_WINDOWS,
            ) { Text("添加时段") }
            if (windows.size >= ScheduleCodec.MAX_WINDOWS) {
                Text(
                    "最多添加 ${ScheduleCodec.MAX_WINDOWS} 个时段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    val language = currentSupportedLanguage()
    MaterialText(
        text = UiText.translate(text, language),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}

@Composable
private fun currentSupportedLanguage(): SupportedLanguage {
    val locale = LocalConfiguration.current.locales.get(0)
    return if (locale?.language == java.util.Locale.CHINESE.language) {
        SupportedLanguage.CHINESE
    } else {
        SupportedLanguage.ENGLISH
    }
}

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
